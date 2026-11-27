package com.hackathon.agent.investigation.model;

import java.math.BigDecimal;
import java.util.List;

/**
 * Final report of an investigation, after validation by the backend.
 *
 * @param failureCategory    a {@code FailureCategory} name
 * @param confidence         0 to 1; at most 0.3 without verified evidence
 * @param evidence           observations found literally in the result of the cited step
 * @param unverifiedEvidence evidence items of the model that were removed because they could not be verified
 */
public record AgentReport(
        String summary,
        String rootCause,
        String failureCategory,
        BigDecimal confidence,
        List<Evidence> evidence,
        List<String> recommendedActions,
        int unverifiedEvidence) {

    /** @param step number of the investigation step whose tool result contains the observation */
    public record Evidence(int step, String observation) {
    }
}
