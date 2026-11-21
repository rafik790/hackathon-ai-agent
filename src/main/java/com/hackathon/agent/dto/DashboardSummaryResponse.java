package com.hackathon.agent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

@Schema(description = """
        Reliability summary for a time window. Rates are percentages over completed executions \
        (succeeded + failed) and are 0 when there is no completed execution. Durations are null when \
        no completed execution exists.""")
public record DashboardSummaryResponse(
        @Schema(example = "2026-11-14T10:00:00Z") Instant from,
        @Schema(example = "2026-11-21T10:00:00Z") Instant to,
        @Schema(example = "120") long totalExecutions,
        @Schema(example = "102") long successfulExecutions,
        @Schema(example = "15") long failedExecutions,
        @Schema(example = "3") long runningExecutions,
        @Schema(example = "0") long unknownExecutions,
        @Schema(example = "87.18") double successRate,
        @Schema(example = "12.82") double failureRate,
        @Schema(example = "184250") Long averageDurationMs,
        @Schema(example = "402000") Long p95DurationMs,
        @Schema(example = "560") long totalTaskRuns,
        @Schema(example = "18") long failedTaskRuns,
        @Schema(example = "3.23") double taskFailureRate) {
}
