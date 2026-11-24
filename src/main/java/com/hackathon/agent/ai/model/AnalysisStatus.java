package com.hackathon.agent.ai.model;

/**
 * Lifecycle of an analysis. It only becomes {@link #COMPLETED} in the transaction that stores its result.
 */
public enum AnalysisStatus {
    PENDING,
    /** Claimed by a worker until its lease ends. */
    PROCESSING,
    COMPLETED,
    RETRY_PENDING,
    FAILED;

    public boolean isInProgress() {
        return this == PENDING || this == PROCESSING || this == RETRY_PENDING;
    }
}
