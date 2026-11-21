package com.hackathon.agent.service;

import java.time.Instant;

/**
 * Reliability metrics for a time window. Rates are percentages (0-100, two decimals) computed over
 * <em>completed</em> executions (succeeded + failed) so that in-flight runs do not dilute them.
 * Durations are {@code null} when no completed execution exists in the window.
 */
public record ReliabilityMetrics(
        Instant from,
        Instant to,
        long totalExecutions,
        long successfulExecutions,
        long failedExecutions,
        long runningExecutions,
        long unknownExecutions,
        double successRate,
        double failureRate,
        Long averageDurationMs,
        Long p95DurationMs,
        long totalTaskRuns,
        long failedTaskRuns,
        double taskFailureRate) {
}
