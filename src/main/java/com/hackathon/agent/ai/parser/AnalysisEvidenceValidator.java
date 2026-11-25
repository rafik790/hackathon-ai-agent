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
 *   <li>If a compiler error proves the cause, an unknown, generic or invented conclusion is replaced by it.</li>
 *   <li>If the cause is a compiler error, the recommended actions are derived from it.</li>
 *   <li>A summary that contradicts the root cause is replaced by it.</li>
 * </ul>
 */
@Component
public class AnalysisEvidenceValidator {

    static final String INSUFFICIENT = "Insufficient evidence: the collected evidence does not confirm a root cause.";
    static final String UNVERIFIED = "Unverified: ";
    static final String UNCONFIRMED = "Unconfirmed root cause: ";
    static final BigDecimal MAX_CONFIDENCE_INSUFFICIENT = new BigDecimal("0.300");
    static final BigDecimal MAX_CONFIDENCE_PARTLY_REJECTED = new BigDecimal("0.600");
    /** The compiler error proves why the build failed, but the conclusion was not made by the model. */
    static final BigDecimal CONFIDENCE_COMPILER_ERROR = new BigDecimal("0.800");
    private static final int MAX_DASHBOARD_TEXT = 300;
    private static final BigDecimal MIN_CONFIDENCE_HIGH = new BigDecimal("0.500");
    private static final BigDecimal MIN_CONFIDENCE_CRITICAL = new BigDecimal("0.800");

    /** Conclusions without content. */
    private static final Pattern GENERIC = Pattern.compile("(?i)^insufficient evidence|\\b(?:root )?cause(?: of [^.]{1,80})? (?:cannot|could not|can't) be"
            + " determined|\\b(?:unable|not enough (?:evidence|information)|not possible) to determine (?:the (?:root )?cause|why)\\b");

    /** Signs, in verified evidence, of an impact beyond a single run. */
    private static final Pattern WIDE_IMPACT = Pattern.compile("(?i)\\b(?:production|cluster|control[ -]plane|all nodes|all pipelines"
            + "|node (?:is )?not ?ready|nodenotready|nodelost|outage|data loss|corrupt(?:ed|ion)?|no space left on device|diskpressure"
            + "|disk pressure)\\b");

    private static final Logger log = LoggerFactory.getLogger(AnalysisEvidenceValidator.class);

    /** What is left of the statements of an answer. */
    private record Checked(List<SupportingEvidence> evidence, List<String> facts, List<String> hypotheses, int rejected,
                           boolean specific) {
    }

    public FailureAnalysisResult validate(FailureAnalysisResult result, FailureContext context) {
        EvidenceMatcher matcher = new EvidenceMatcher(context);
        Checked checked = check(result, matcher, context);

        boolean causeInvented = matcher.containsInventedClaims(result.rootCause());
        boolean summaryInvented = matcher.containsInventedClaims(result.summary());
        boolean causeUsable = !causeInvented && !isGeneric(result.rootCause());
        boolean admitted = result.failureCategory() == FailureCategory.UNKNOWN && !causeInvented && !summaryInvented
                && result.confidence().compareTo(MAX_CONFIDENCE_INSUFFICIENT) <= 0;
        boolean insufficient = !admitted && (!checked.specific() || !causeUsable);
        List<CompilerError> errors = CompilerError.findAll(context);
        // The first error is the primary one; later ones are often caused by it.
        CompilerError error = errors.isEmpty() ? null : errors.getFirst();
        boolean ignoresError = error != null && (insufficient || !causeUsable || (result.failureCategory() == FailureCategory.UNKNOWN
                && (error.isMentionedIn(result.rootCause()) || isCited(error, checked.evidence()))));

        if (checked.rejected() > 0 || insufficient || summaryInvented || ignoresError) {
            log.warn("Analysis of {} corrected: rejectedStatements={} specificEvidence={} inventedRootCause={} inventedSummary={} "
                            + "outcome={}", context.taskRun().name(), checked.rejected(), checked.specific(), causeInvented,
                    summaryInvented, ignoresError ? "compiler error" : insufficient ? "insufficient evidence" : "reduced");
        }
        // Only used if the compiler error is the cause.
        List<RecommendedAction> actions = error == null ? result.recommendedActions()
                : CompilerErrorActions.derive(errors, result.recommendedActions(), matcher, context);
        if (ignoresError) {
            return useCompilerError(result, checked, error, causeUsable, summaryInvented, actions);
        }
        if (insufficient) {
            return insufficient(result, checked, matcher, context, causeInvented, causeUsable);
        }
        return keep(result, checked, error, admitted, summaryInvented, actions);
    }

    private static Checked check(FailureAnalysisResult result, EvidenceMatcher matcher, FailureContext context) {
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
        if (facts.isEmpty()) {
            facts.addAll(knownFacts(context));
        }
        return new Checked(evidence, facts, hypotheses, rejected, specific);
    }

    /** The conclusion of the model is supported; only fields that contradict it are adjusted. */
    private static FailureAnalysisResult keep(FailureAnalysisResult result, Checked checked, CompilerError error, boolean admitted,
                                              boolean summaryInvented, List<RecommendedAction> errorActions) {
        boolean causeIsError = error != null && error.isMentionedIn(result.rootCause());
        String summary = result.summary();
        // An answer that admits not to know the cause may have a generic summary.
        if (summaryInvented || (!admitted && isGeneric(summary))) {
            summary = causeIsError ? error.summary() : shortened(result.rootCause());
        }
        BigDecimal confidence = limitedConfidence(result, checked, summaryInvented);
        if (causeIsError && result.failureCategory() == FailureCategory.COMPILATION) {
            confidence = confidence.max(compilerErrorConfidence(checked, summaryInvented));
        }
        return build(result, checked, summary, result.failureCategory(), result.rootCause(), confidence, admitted,
                checked.evidence(), causeIsError ? errorActions : result.recommendedActions());
    }

    /** The cause is not supported and nothing else proves one. */
    private static FailureAnalysisResult insufficient(FailureAnalysisResult result, Checked checked, EvidenceMatcher matcher,
                                                      FailureContext context, boolean causeInvented, boolean causeUsable) {
        if (causeUsable) {
            checked.hypotheses().addFirst(limit(UNCONFIRMED + result.rootCause()));
        }
        // An explanation of what is missing is kept.
        String rootCause = causeInvented || causeUsable ? INSUFFICIENT : result.rootCause();
        return build(result, checked, unconfirmedSummary(matcher, checked.evidence(), context), FailureCategory.UNKNOWN, rootCause,
                result.confidence().min(MAX_CONFIDENCE_INSUFFICIENT), true, checked.evidence(),
                unconfirmedActions(result.recommendedActions()));
    }

    /** The log proves a compiler error, but the answer does not draw that conclusion. */
    private static FailureAnalysisResult useCompilerError(FailureAnalysisResult result, Checked checked, CompilerError error,
                                                          boolean causeUsable, boolean summaryInvented,
                                                          List<RecommendedAction> actions) {
        String rootCause = result.rootCause();
        String summary = summaryInvented || isGeneric(result.summary()) ? error.summary() : result.summary();
        FailureCategory category = result.failureCategory();
        BigDecimal confidence = limitedConfidence(result, checked, summaryInvented).max(compilerErrorConfidence(checked, summaryInvented));
        if (!causeUsable || !error.isMentionedIn(rootCause)) {
            if (causeUsable) {
                checked.hypotheses().addFirst(limit(UNCONFIRMED + rootCause));
            }
            rootCause = error.rootCause();
            summary = error.summary();
            category = FailureCategory.COMPILATION;
            confidence = CONFIDENCE_COMPILER_ERROR;
        } else if (category == FailureCategory.UNKNOWN) {
            category = FailureCategory.COMPILATION;
        }
        List<SupportingEvidence> evidence = new ArrayList<>(checked.evidence());
        if (!isCited(error, evidence)) {
            evidence.addFirst(error.evidence());
        }
        return build(result, checked, summary, category, rootCause, confidence, false, evidence, actions);
    }

    private static FailureAnalysisResult build(FailureAnalysisResult result, Checked checked, String summary, FailureCategory category,
                                               String rootCause, BigDecimal confidence, boolean uncertain,
                                               List<SupportingEvidence> evidence, List<RecommendedAction> actions) {
        return new FailureAnalysisResult(summary, category, rootCause, severity(result.severity(), confidence, evidence, uncertain),
                confidence, List.copyOf(checked.facts()), bounded(checked.hypotheses()), List.copyOf(evidence), actions,
                result.preventiveRecommendations());
    }

    private static boolean isCited(CompilerError error, List<SupportingEvidence> evidence) {
        return evidence.stream().anyMatch(item -> error.isMentionedIn(item.observation()));
    }

    private static BigDecimal limitedConfidence(FailureAnalysisResult result, Checked checked, boolean summaryInvented) {
        return checked.rejected() > 0 || summaryInvented ? result.confidence().min(MAX_CONFIDENCE_PARTLY_REJECTED) : result.confidence();
    }

    private static BigDecimal compilerErrorConfidence(Checked checked, boolean summaryInvented) {
        return checked.rejected() > 0 || summaryInvented ? MAX_CONFIDENCE_PARTLY_REJECTED : CONFIDENCE_COMPILER_ERROR;
    }

    /** Whether a text only says that the cause is not known. */
    private static boolean isGeneric(String text) {
        return GENERIC.matcher(text.strip()).find();
    }

    /** Shows what is verified, although it does not confirm a cause. */
    private static String unconfirmedSummary(EvidenceMatcher matcher, List<SupportingEvidence> evidence, FailureContext context) {
        return evidence.stream().filter(item -> matcher.match(item) == Match.SPECIFIC).findFirst()
                .map(item -> "Cause not confirmed. " + (item.containerName() == null ? "Reported: " : item.containerName() + " reported: ")
                        + shortened(item.observation()))
                .orElse("Insufficient evidence to determine why TaskRun " + context.taskRun().name() + " failed.");
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

    private static String shortened(String text) {
        return text.length() <= MAX_DASHBOARD_TEXT ? text : text.substring(0, MAX_DASHBOARD_TEXT - 3) + "...";
    }
}
