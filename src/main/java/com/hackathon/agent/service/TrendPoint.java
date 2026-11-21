package com.hackathon.agent.service;

import java.time.Instant;

/**
 * Aggregated pipeline executions for one time bucket (bucket start inclusive, UTC aligned).
 */
public record TrendPoint(
        Instant bucketStart,
        long total,
        long succeeded,
        long failed,
        long running,
        long unknown,
        double successRate,
        Long averageDurationMs) {
}
