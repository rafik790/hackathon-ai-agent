package com.hackathon.agent.mapper;

import com.hackathon.agent.ai.entity.FailureAnalysisEntity;
import com.hackathon.agent.ai.model.ConfidenceLevel;
import com.hackathon.agent.ai.repository.FailureAnalysisRepository.AnalysisView;
import com.hackathon.agent.dto.FailureAnalysisResponse;
import com.hackathon.agent.dto.FailureAnalysisSummaryResponse;

/**
 * Converts analyses into API DTOs.
 */
public final class FailureAnalysisMapper {

    private FailureAnalysisMapper() {
    }

    public static FailureAnalysisSummaryResponse toSummary(AnalysisView view) {
        FailureAnalysisEntity a = view.analysis();
        return new FailureAnalysisSummaryResponse(a.getId(), a.getFailureEvidenceId(), view.taskRunId(),
                view.taskRunName(), view.taskName(), view.pipelineRunName(), view.namespace(), view.attempt(),
                a.getAnalysisStatus(), a.getFailureCategory(), a.getSeverity(), a.getConfidence(), level(a),
                a.getSummary(), a.getAnalysedAt(), a.getCreatedAt());
    }

    public static FailureAnalysisResponse toResponse(AnalysisView view) {
        FailureAnalysisEntity a = view.analysis();
        return new FailureAnalysisResponse(a.getId(), a.getFailureEvidenceId(), view.taskRunId(), view.taskRunName(),
                view.taskName(), view.pipelineRunName(), view.namespace(), view.attempt(), a.getAnalysisStatus(),
                a.getFailureCategory(), a.getSeverity(), a.getConfidence(), level(a), a.getSummary(),
                a.getRootCause(), a.getObservedFacts(), a.getHypotheses(), a.getSupportingEvidence(),
                a.getRecommendedActions(), a.getPreventiveRecommendations(), a.getProvider(), a.getModel(),
                a.getAnalysisVersion(), a.getPromptVersion(), a.getRevision(), a.getInputTokens(),
                a.getOutputTokens(), a.getProcessingAttempts(), a.getLastError(), a.getNextAttemptAt(),
                a.getAnalysedAt(), a.getCreatedAt(), a.getUpdatedAt());
    }

    private static ConfidenceLevel level(FailureAnalysisEntity a) {
        return a.getConfidence() == null ? null : ConfidenceLevel.of(a.getConfidence().doubleValue());
    }
}
