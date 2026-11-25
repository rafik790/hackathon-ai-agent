package com.hackathon.agent.ai.parser;

import com.hackathon.agent.ai.model.FailureAnalysisResult;
import com.hackathon.agent.ai.model.FailureCategory;
import com.hackathon.agent.ai.model.FailureContext;
import com.hackathon.agent.ai.model.RecommendationPriority;
import com.hackathon.agent.ai.model.RecommendedAction;
import com.hackathon.agent.ai.model.Severity;
import com.hackathon.agent.ai.model.SupportingEvidence;
import com.hackathon.agent.ai.parser.EvidenceMatcher.Match;
import com.hackathon.agent.evidence.model.ExitCodeSource;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Reduces an analysis to what the context supports. Deterministic and without another model call:
 * <ul>
 *   <li>Supporting evidence that cannot be found in its source is removed.</li>
 *   <li>Facts with invented details are removed; facts that cannot be verified become hypotheses.</li>
 *   <li>Without specific evidence the root cause becomes a hypothesis and the result says that the evidence is
 *       insufficient.</li>
 *   <li>Confidence and severity are lowered to what the remaining evidence allows.</li>
 * </ul>
 */
@Component
public class AnalysisEvidenceValidator {

    static final String INSUFFICIENT = "Insufficient evidence: the collected evidence does not confirm a root cause.";
    static final String UNVERIFIED = "Unverified: ";
    static final String UNCONFIRMED = "Unconfirmed root cause: ";
    static final BigDecimal MAX_CONFIDENCE_INSUFFICIENT = new BigDecimal("0.300");
    static final BigDecimal MAX_CONFIDENCE_PARTLY_REJECTED = new BigDecimal("0.600");
    private static final BigDecimal MIN_CONFIDENCE_HIGH = new BigDecimal("0.500");
    private static final BigDecimal MIN_CONFIDENCE_CRITICAL = new BigDecimal("0.800");

    /** Signs, in verified evidence, of an impact beyond a single run. */
    private static final Pattern WIDE_IMPACT = Pattern.compile("(?i)\\b(?:production|cluster|control[ -]plane|all nodes|all pipelines"
            + "|node (?:is )?not ?ready|nodenotready|nodelost|outage|data loss|corrupt(?:ed|ion)?|no space left on device|diskpressure"
            + "|disk pressure)\\b");

    private static final Logger log = LoggerFactory.getLogger(AnalysisEvidenceValidator.class);

    public FailureAnalysisResult validate(FailureAnalysisResult result, FailureContext context) {
        EvidenceMatcher matcher = new EvidenceMatcher(context);

        List<SupportingEvidence> evidence = new ArrayList<>();
        boolean specific = false;
        for (SupportingEvidence item : result.supportingEvidence()) {
            Match match = matcher.match(item);
            if (match != Match.NONE) {
                evidence.add(item);
                specific |= match == Match.SPECIFIC;
            }
        }

        List<String> facts = new ArrayList<>();
        List<String> hypotheses = new ArrayList<>();
        int rejected = result.supportingEvidence().size() - evidence.size();
        for (String hypothesis : result.hypotheses()) {
            if (matcher.containsInventedIdentifiers(hypothesis)) {
                rejected++;
            } else {
                add(hypotheses, "", hypothesis);
            }
        }
        for (String fact : result.observedFacts()) {
            if (matcher.containsInventedDetails(fact)) {
                rejected++;
            } else if (matcher.isGrounded(fact)) {
                facts.add(fact);
            } else {
                add(hypotheses, UNVERIFIED, fact);
            }
        }

        boolean invented = matcher.containsInventedClaims(result.rootCause()) || matcher.containsInventedClaims(result.summary());
        boolean admitted = result.failureCategory() == FailureCategory.UNKNOWN && !invented
                && result.confidence().compareTo(MAX_CONFIDENCE_INSUFFICIENT) <= 0;
        boolean insufficient = !admitted && (!specific || invented);
        if (rejected > 0 || insufficient) {
            log.warn("Analysis of {} reduced to its evidence: rejectedStatements={} insufficientEvidence={} specificEvidence={} "
                    + "inventedConclusion={}", context.taskRun().name(), rejected, insufficient, specific, invented);
        }
        if (facts.isEmpty()) {
            facts.addAll(knownFacts(context));
        }

        BigDecimal confidence = result.confidence();
        if (rejected > 0) {
            confidence = confidence.min(MAX_CONFIDENCE_PARTLY_REJECTED);
        }
        if (!insufficient) {
            return new FailureAnalysisResult(result.summary(), result.failureCategory(), result.rootCause(),
                    severity(result.severity(), confidence, evidence, admitted), confidence, List.copyOf(facts),
                    List.copyOf(hypotheses), List.copyOf(evidence), result.recommendedActions(),
                    result.preventiveRecommendations());
        }
        if (!matcher.containsInventedClaims(result.rootCause())) {
            hypotheses.addFirst(limit(UNCONFIRMED + result.rootCause()));
        }
        confidence = confidence.min(MAX_CONFIDENCE_INSUFFICIENT);
        return new FailureAnalysisResult("Insufficient evidence to determine why TaskRun " + context.taskRun().name() + " failed.",
                FailureCategory.UNKNOWN, INSUFFICIENT, severity(result.severity(), confidence, evidence, true), confidence,
                List.copyOf(facts), bounded(hypotheses), List.copyOf(evidence), unconfirmedActions(result.recommendedActions()),
                result.preventiveRecommendations());
    }

    /** A single failed run is not critical by itself, and an uncertain cause does not justify a high severity. */
    private static Severity severity(Severity requested, BigDecimal confidence, List<SupportingEvidence> evidence,
                                     boolean insufficient) {
        Severity severity = requested;
        if (severity == Severity.CRITICAL && (confidence.compareTo(MIN_CONFIDENCE_CRITICAL) < 0
                || evidence.stream().noneMatch(e -> WIDE_IMPACT.matcher(e.observation()).find()))) {
            severity = Severity.HIGH;
        }
        if (severity == Severity.HIGH && (insufficient || confidence.compareTo(MIN_CONFIDENCE_HIGH) < 0)) {
            severity = Severity.MEDIUM;
        }
        return severity;
    }

    /** Actions for a cause that is not confirmed are kept, but a person should look first. */
    private static List<RecommendedAction> unconfirmedActions(List<RecommendedAction> actions) {
        List<RecommendedAction> result = new ArrayList<>();
        result.add(new RecommendedAction(RecommendationPriority.HIGH, "Inspect the TaskRun, its Pod events and its logs manually",
                "The collected evidence does not confirm a root cause"));
        actions.stream().limit(FailureAnalysisParser.MAX_ITEMS - 1L).forEach(action -> result.add(
                new RecommendedAction(RecommendationPriority.LOW, action.action(), action.reason())));
        return List.copyOf(result);
    }

    /** Facts taken directly from the context, for answers without any verifiable fact. */
    private static List<String> knownFacts(FailureContext context) {
        List<String> facts = new ArrayList<>();
        FailureContext.TaskRun taskRun = context.taskRun();
        facts.add("TaskRun " + taskRun.name() + " has status " + taskRun.status()
                + (taskRun.failureReason() == null ? "" : " and reason " + taskRun.failureReason()));
        context.containers().stream()
                .filter(c -> c.exitCode() != null && c.exitCode() != 0 && c.exitCodeSource() != ExitCodeSource.SYNTHETIC)
                .limit(5)
                .forEach(c -> facts.add("Container " + c.name() + " has exit code " + c.exitCode()));
        return facts;
    }

    private static void add(List<String> target, String prefix, String text) {
        String entry = limit(prefix + text);
        if (target.size() < FailureAnalysisParser.MAX_ITEMS && !target.contains(entry)) {
            target.add(entry);
        }
    }

    private static List<String> bounded(List<String> list) {
        return List.copyOf(list.size() > FailureAnalysisParser.MAX_ITEMS ? list.subList(0, FailureAnalysisParser.MAX_ITEMS) : list);
    }

    private static String limit(String text) {
        return text.length() <= FailureAnalysisParser.MAX_TEXT ? text : text.substring(0, FailureAnalysisParser.MAX_TEXT);
    }
}
