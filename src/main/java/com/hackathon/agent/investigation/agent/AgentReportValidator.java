package com.hackathon.agent.investigation.agent;

import com.hackathon.agent.ai.model.FailureCategory;
import com.hackathon.agent.evidence.sanitizer.LogSanitizer;
import com.hackathon.agent.investigation.agent.AgentDecision.ProposedEvidence;
import com.hackathon.agent.investigation.agent.AgentDecision.ProposedReport;
import com.hackathon.agent.investigation.model.AgentReport;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Accepts only what the investigation can back: evidence must be found literally in the result of the step it cites,
 * and without such evidence the confidence is limited. All text is redacted and bounded.
 */
public class AgentReportValidator {

    static final BigDecimal MAX_CONFIDENCE_UNVERIFIED = new BigDecimal("0.300");
    static final int MAX_SUMMARY = 2000;
    static final int MAX_ROOT_CAUSE = 4000;
    static final int MAX_TEXT = 1000;
    private static final int MIN_OBSERVATION = 4;

    private final LogSanitizer sanitizer;

    public AgentReportValidator(LogSanitizer sanitizer) {
        this.sanitizer = sanitizer;
    }

    /**
     * @param toolResults results of the successful tool steps, by step number
     */
    public AgentReport validate(ProposedReport report, Map<Integer, String> toolResults) {
        List<AgentReport.Evidence> verified = new ArrayList<>();
        int unverified = 0;
        for (ProposedEvidence item : report.evidence()) {
            String result = toolResults.get(item.step());
            String observation = sanitizer.sanitizeMessage(item.observation(), MAX_TEXT);
            if (result != null && observation != null && isQuoted(observation, result)) {
                verified.add(new AgentReport.Evidence(item.step(), observation));
            } else {
                unverified++;
            }
        }
        BigDecimal confidence = report.confidence().max(BigDecimal.ZERO).min(BigDecimal.ONE).setScale(3, RoundingMode.HALF_UP);
        if (verified.isEmpty()) {
            confidence = confidence.min(MAX_CONFIDENCE_UNVERIFIED);
        }
        return new AgentReport(
                text(report.summary() == null ? report.rootCause() : report.summary(), MAX_SUMMARY),
                text(report.rootCause(), MAX_ROOT_CAUSE),
                category(report.failureCategory()),
                confidence,
                List.copyOf(verified),
                report.recommendedActions().stream().map(action -> text(action, MAX_TEXT)).toList(),
                unverified);
    }

    /** Whether the observation, apart from whitespace and case, occurs in the tool result. */
    static boolean isQuoted(String observation, String result) {
        String quote = normalize(observation);
        return quote.length() >= MIN_OBSERVATION && normalize(unescapeJson(result)).contains(quote);
    }

    private static String category(String value) {
        if (value != null) {
            try {
                return FailureCategory.valueOf(value.trim().toUpperCase(Locale.ROOT)).name();
            } catch (IllegalArgumentException e) {
                // unknown categories become UNKNOWN
            }
        }
        return FailureCategory.UNKNOWN.name();
    }

    private String text(String value, int max) {
        String text = sanitizer.sanitizeMessage(value, max);
        return text == null ? "" : text;
    }

    /** Tool results are JSON, so quotes and line breaks in them are escaped. */
    private static String unescapeJson(String text) {
        return text.replace("\\\"", "\"").replace("\\n", "\n").replace("\\t", "\t").replace("\\\\", "\\");
    }

    private static String normalize(String text) {
        return text.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").strip();
    }
}
