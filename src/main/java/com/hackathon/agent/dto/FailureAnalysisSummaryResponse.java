package com.hackathon.agent.dto;

import com.hackathon.agent.ai.model.AnalysisStatus;
import com.hackathon.agent.ai.model.ConfidenceLevel;
import com.hackathon.agent.ai.model.FailureCategory;
import com.hackathon.agent.ai.model.Severity;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;

@Schema(description = "An AI-generated failure analysis in a list. Result fields are null until it is COMPLETED.")
public record FailureAnalysisSummaryResponse(
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
        @Schema(example = "2026-11-25T10:02:11Z") Instant analysedAt,
        @Schema(example = "2026-11-25T10:01:40Z") Instant createdAt) {
}
