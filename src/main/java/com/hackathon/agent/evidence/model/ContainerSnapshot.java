package com.hackathon.agent.evidence.model;

import java.time.Instant;

/**
 * Normalized state of one container, taken from the Pod, the TaskRun or both.
 */
public record ContainerSnapshot(
        String name,
        ContainerType type,
        String stepName,
        Integer ordinal,
        ContainerRunState state,
        Integer exitCode,
        ExitCodeSource exitCodeSource,
        Integer signal,
        String reason,
        String tektonTerminationReason,
        String message,
        Integer restartCount,
        Instant startedAt,
        Instant finishedAt) {

    public boolean hasRestarted() {
        return restartCount != null && restartCount > 0;
    }
}
