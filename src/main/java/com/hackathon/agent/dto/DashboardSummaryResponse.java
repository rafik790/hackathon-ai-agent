package com.hackathon.agent.dto;

import com.hackathon.agent.resolution.model.ResolutionStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.Map;

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
        @Schema(example = "3.23") double taskFailureRate,
        @Schema(description = "The failed TaskRuns of the window by resolution status; every status is present and they add up "
                + "to failedTaskRuns. A failed TaskRun without a resolution record counts as OPEN.",
                example = "{\"OPEN\": 10, \"INVESTIGATING\": 3, \"FIX_IN_PROGRESS\": 2, \"RESOLVED\": 3, \"REOPENED\": 0}")
        Map<ResolutionStatus, Long> failedTaskRunsByResolution) {

    /** A summary without resolution counts, which are then all zero. */
    public DashboardSummaryResponse(Instant from, Instant to, long totalExecutions, long successfulExecutions, long failedExecutions,
                                    long runningExecutions, long unknownExecutions, double successRate, double failureRate,
                                    Long averageDurationMs, Long p95DurationMs, long totalTaskRuns, long failedTaskRuns,
                                    double taskFailureRate) {
        this(from, to, totalExecutions, successfulExecutions, failedExecutions, runningExecutions, unknownExecutions, successRate,
                failureRate, averageDurationMs, p95DurationMs, totalTaskRuns, failedTaskRuns, taskFailureRate, zeroCounts());
    }

    private static Map<ResolutionStatus, Long> zeroCounts() {
        Map<ResolutionStatus, Long> counts = new java.util.EnumMap<>(ResolutionStatus.class);
        for (ResolutionStatus status : ResolutionStatus.values()) {
            counts.put(status, 0L);
        }
        return counts;
    }
}
