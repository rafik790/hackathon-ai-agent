package com.hackathon.agent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@Schema(description = "State of the Tekton collector. Cycle fields are null until the first cycle completes.")
public record CollectorStatusResponse(
        @Schema(example = "true") boolean enabled,
        @Schema(example = "[\"ci\"]") List<String> namespaces,
        @Schema(example = "15000") long pollingIntervalMs,
        @Schema(example = "240") long totalCycles,
        @Schema(example = "0") int consecutiveFailedCycles,
        @Schema(example = "2026-11-21T10:01:25Z") Instant lastSuccessfulCycleAt,
        LastCycle lastCycle) {

    public record LastCycle(
            @Schema(example = "2026-11-21T10:01:24Z") Instant startedAt,
            @Schema(example = "2026-11-21T10:01:25Z") Instant finishedAt,
            @Schema(example = "412") long durationMs,
            @Schema(example = "true") boolean successful,
            @Schema(example = "{\"PipelineRun\": 12, \"TaskRun\": 48}") Map<String, Integer> discovered,
            @Schema(example = "1") int created,
            @Schema(example = "3") int updated,
            @Schema(example = "56") int unchanged,
            @Schema(example = "0") int skipped,
            List<String> errors) {
    }
}
