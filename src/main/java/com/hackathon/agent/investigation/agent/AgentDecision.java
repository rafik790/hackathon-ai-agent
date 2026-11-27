package com.hackathon.agent.investigation.agent;

import java.math.BigDecimal;
import java.util.List;

/**
 * What the model decided in one step, as parsed but not yet validated: run a tool, or finish with a report.
 *
 * @param report only used when {@code action} is FINAL_REPORT
 */
public record AgentDecision(String reasoning, Action action, String toolName, String resourceName, String namespace,
                            ProposedReport report) {

    public enum Action {
        CALL_TOOL, FINAL_REPORT
    }

    /** The report as the model wrote it; nothing in it is trusted yet. */
    public record ProposedReport(String summary, String rootCause, String failureCategory, BigDecimal confidence,
                                 List<ProposedEvidence> evidence, List<String> recommendedActions) {
    }

    public record ProposedEvidence(int step, String observation) {
    }
}
