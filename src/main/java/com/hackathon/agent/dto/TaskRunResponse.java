package com.hackathon.agent.dto;

import com.hackathon.agent.enums.ExecutionStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

@Schema(description = "A Tekton TaskRun execution")
public record TaskRunResponse(
        @Schema(example = "311") Long id,
        @Schema(example = "0b7e8f0e-8d0c-4a43-b1a4-5c3f0b6a1d22") String taskRunUid,
        @Schema(example = "demo-app-build-x7k2p-unit-tests") String taskRunName,
        @Schema(example = "ci") String namespace,
        @Schema(example = "6f1c2c9e-3f5b-4c1e-9a43-1b1f0f6f7a10") String pipelineRunUid,
        @Schema(example = "demo-app-build-x7k2p") String pipelineRunName,
        @Schema(example = "demo-app-build") String pipelineName,
        @Schema(example = "unit-tests") String taskName,
        @Schema(example = "FAILED") ExecutionStatus status,
        @Schema(example = "2026-11-21T09:59:40Z") Instant startTime,
        @Schema(example = "2026-11-21T10:01:12Z") Instant completionTime,
        @Schema(example = "92000") Long durationMs,
        @Schema(example = "Failed") String failureReason,
        @Schema(example = "\"step-test\" exited with code 1") String failureMessage,
        @Schema(example = "2026-11-21T09:59:39Z") Instant resourceCreatedAt,
        @Schema(example = "2026-11-21T09:59:45Z") Instant createdAt,
        @Schema(example = "2026-11-21T10:01:25Z") Instant updatedAt) {
}
