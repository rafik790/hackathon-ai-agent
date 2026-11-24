package com.hackathon.agent.dto;

import com.hackathon.agent.ai.model.AnalysisStatus;
import com.hackathon.agent.ai.model.ConfidenceLevel;
import com.hackathon.agent.ai.model.FailureCategory;
import com.hackathon.agent.ai.model.RecommendedAction;
import com.hackathon.agent.ai.model.Severity;
import com.hackathon.agent.ai.model.SupportingEvidence;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@Schema(description = "An AI-generated failure analysis. It may be wrong: verify it before acting on it. "
        + "Result fields are null until the status is COMPLETED.")
public record FailureAnalysisResponse(
        @Schema(example = "7") Long id,
        @Schema(example = "12") Long failureEvidenceId,
        @Schema(example = "311") Long taskRunId,
        @Schema(example = "demo-app-build-x7k2p-build-and-push") String taskRunName,
        @Schema(example = "build-and-push") String taskName,
        @Schema(example = "demo-app-build-x7k2p") String pipelineRunName,
        @Schema(example = "ci") String namespace,
        @Schema(description = "Zero-based attempt of the TaskRun", example = "0") int attempt,
        @Schema(example = "COMPLETED") AnalysisStatus status,
        @Schema(example = "REGISTRY_AUTHENTICATION") FailureCategory failureCategory,
        @Schema(example = "HIGH") Severity severity,
        @Schema(example = "0.92") BigDecimal confidence,
        @Schema(example = "HIGH") ConfidenceLevel confidenceLevel,
        @Schema(example = "Pushing the image failed because the registry rejected the credentials.") String summary,
        @Schema(example = "The registry rejected the credentials used by step build-and-push.") String rootCause,
        List<String> observedFacts,
        List<String> hypotheses,
        List<SupportingEvidence> supportingEvidence,
        List<RecommendedAction> recommendedActions,
        List<String> preventiveRecommendations,
        @Schema(example = "anthropic") String provider,
        @Schema(description = "Model that answered") String model,
        @Schema(example = "1") int analysisVersion,
        @Schema(example = "v1") String promptVersion,
        @Schema(description = "Above 0 for manual re-analyses", example = "0") int revision,
        @Schema(description = "Over all attempts", example = "5120") int inputTokens,
        @Schema(description = "Over all attempts", example = "870") int outputTokens,
        @Schema(example = "1") int processingAttempts,
        @Schema(description = "Reason of the last failed attempt") String lastError,
        Instant nextAttemptAt,
        @Schema(example = "2026-11-25T10:02:11Z") Instant analysedAt,
        @Schema(example = "2026-11-25T10:01:40Z") Instant createdAt,
        @Schema(example = "2026-11-25T10:02:11Z") Instant updatedAt) {
}
