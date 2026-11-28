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

    /**
     * The report as the model wrote it; nothing in it is trusted yet. Team and confidence names are raw strings, so
     * that an unknown value can be normalized instead of rejecting the whole report.
     */
    public record ProposedReport(String summary, String rootCause, String failureCategory, BigDecimal confidence,
                                 List<ProposedEvidence> evidence, List<String> recommendedActions,
                                 String title, String ownerTeam, String ownershipConfidence, String ownershipReason,
                                 List<ProposedStep> resolutionSteps, List<ProposedTeamCheck> otherTeamChecks,
                                 List<ProposedStep> verificationSteps, String expectedOutcome,
                                 List<String> missingInformation, String nextDiagnosticAction) {

        /** A report without ownership and resolution, as written before those fields existed. */
        public ProposedReport(String summary, String rootCause, String failureCategory, BigDecimal confidence,
                              List<ProposedEvidence> evidence, List<String> recommendedActions) {
            this(summary, rootCause, failureCategory, confidence, evidence, recommendedActions, null, null, null, null,
                    List.of(), List.of(), List.of(), null, List.of(), null);
        }
    }

    public record ProposedEvidence(int step, String observation) {
    }

    public record ProposedStep(String action, String command) {
    }

    public record ProposedTeamCheck(String team, String request) {
    }
}
