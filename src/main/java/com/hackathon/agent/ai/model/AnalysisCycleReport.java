package com.hackathon.agent.ai.model;

import java.time.Instant;

/**
 * Counters of one analysis cycle.
 */
public record AnalysisCycleReport(Instant finishedAt, long durationMs, int enqueued, int processed, int completed,
                                  int retried, int failed, boolean paused, boolean quotaExhausted) {
}
