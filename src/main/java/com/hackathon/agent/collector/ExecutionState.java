package com.hackathon.agent.collector;

import com.hackathon.agent.enums.ExecutionStatus;
import java.time.Instant;

/**
 * Normalized execution state of a Tekton run, independent of the resource kind.
 * {@code failureReason}/{@code failureMessage} are only populated for FAILED runs.
 */
public record ExecutionState(
        ExecutionStatus status,
        Instant startTime,
        Instant completionTime,
        Long durationMs,
        String failureReason,
        String failureMessage) {
}
