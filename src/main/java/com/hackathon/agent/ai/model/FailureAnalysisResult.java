package com.hackathon.agent.ai.model;

import java.math.BigDecimal;
import java.util.List;

/**
 * A validated analysis.
 */
public record FailureAnalysisResult(
        String summary,
        FailureCategory failureCategory,
        String rootCause,
        Severity severity,
        BigDecimal confidence,
        List<String> observedFacts,
        List<String> hypotheses,
        List<SupportingEvidence> supportingEvidence,
        List<RecommendedAction> recommendedActions,
        List<String> preventiveRecommendations) {
}
