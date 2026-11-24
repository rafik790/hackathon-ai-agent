package com.hackathon.agent.evidence.model;

/**
 * Outcome of one log stream.
 */
public enum LogStatus {
    COLLECTED,
    /** The container produced no output. */
    EMPTY,
    /** The container never started, so there is no log to request. */
    NOT_STARTED,
    /** Kubernetes has no such log (400/404). */
    NOT_AVAILABLE,
    /** The node no longer has the log file. */
    EXPIRED,
    /** Not stored because the log limit of the TaskRun is used up. */
    SKIPPED_LIMIT,
    /** Access denied; retried while the evidence is PENDING. */
    FORBIDDEN,
    /** Transient error; retried while the evidence is PENDING. */
    ERROR;

    public boolean isRetryable() {
        return this == FORBIDDEN || this == ERROR;
    }

    /** The log is as complete as the container made it. */
    public boolean isComplete() {
        return this == COLLECTED || this == EMPTY;
    }
}
