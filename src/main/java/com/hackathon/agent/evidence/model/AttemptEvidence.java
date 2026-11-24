package com.hackathon.agent.evidence.model;

import java.time.Instant;
import java.util.List;

/**
 * Everything collected for one TaskRun attempt, persisted in a single transaction.
 *
 * @param pod              null when no Pod could be read
 * @param containers       container states from the Pod, or from the TaskRun when {@code pod} is null
 * @param primaryContainer name of the primary failing container, or null
 * @param nextAttemptAt    only set for {@link EvidenceStatus#PENDING}
 */
public record AttemptEvidence(
        int attempt,
        EvidenceStatus status,
        String podName,
        PodSnapshot pod,
        List<ContainerSnapshot> containers,
        String primaryContainer,
        List<LogSnapshot> logs,
        int fetchAttempts,
        Instant nextAttemptAt,
        String error) {
}
