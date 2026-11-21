package com.hackathon.agent.dto;

import com.hackathon.agent.enums.ExecutionStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

@Schema(description = "A Tekton PipelineRun execution")
public record PipelineRunResponse(
        @Schema(example = "42") Long id,
        @Schema(example = "6f1c2c9e-3f5b-4c1e-9a43-1b1f0f6f7a10") String pipelineRunUid,
        @Schema(example = "demo-app-build-x7k2p") String pipelineRunName,
        @Schema(example = "ci") String namespace,
        @Schema(example = "demo-app-build") String pipelineName,
        @Schema(example = "FAILED") ExecutionStatus status,
        @Schema(example = "2026-11-21T09:58:02Z") Instant startTime,
        @Schema(example = "2026-11-21T10:01:17Z") Instant completionTime,
        @Schema(example = "195000") Long durationMs,
        @Schema(example = "Failed") String failureReason,
        @Schema(example = "Tasks Completed: 3 (Failed: 1, Cancelled 0), Skipped: 1") String failureMessage,
        @Schema(description = "Kubernetes creationTimestamp", example = "2026-11-21T09:58:01Z") Instant resourceCreatedAt,
        @Schema(description = "When the record was first collected", example = "2026-11-21T09:58:10Z") Instant createdAt,
        @Schema(description = "When the record last changed", example = "2026-11-21T10:01:25Z") Instant updatedAt) {
}
