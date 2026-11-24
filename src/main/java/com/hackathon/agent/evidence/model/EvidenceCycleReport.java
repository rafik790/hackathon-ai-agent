package com.hackathon.agent.evidence.model;

import java.time.Instant;

/**
 * Counters of one evidence collection cycle.
 *
 * @param failedApiCalls    all failed Kubernetes requests, including the forbidden ones
 * @param forbiddenApiCalls requests rejected with 401/403
 * @param taskRunErrors     TaskRuns whose evidence could not be processed or persisted
 */
public record EvidenceCycleReport(
        Instant startedAt,
        Instant finishedAt,
        long durationMs,
        int taskRunsExamined,
        int evidenceCreated,
        int evidenceUpdated,
        int logsFetched,
        int logsSkipped,
        int failedApiCalls,
        int forbiddenApiCalls,
        int taskRunErrors) {

    public boolean successful() {
        return failedApiCalls == 0 && taskRunErrors == 0;
    }
}
