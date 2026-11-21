package com.hackathon.agent.collector;

import java.time.Duration;
import java.time.Instant;

/**
 * Execution duration rules shared by PipelineRuns and TaskRuns.
 */
public final class DurationCalculator {

    private DurationCalculator() {
    }

    /**
     * @return milliseconds between start and completion, {@code null} when either timestamp is missing
     *         (e.g. the run is still in progress). Negative values caused by clock skew are clamped to 0.
     */
    public static Long durationMs(Instant startTime, Instant completionTime) {
        if (startTime == null || completionTime == null) {
            return null;
        }
        return Math.max(0L, Duration.between(startTime, completionTime).toMillis());
    }
}
