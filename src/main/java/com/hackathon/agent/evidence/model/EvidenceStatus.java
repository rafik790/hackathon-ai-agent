package com.hackathon.agent.evidence.model;

/**
 * State of the evidence for one TaskRun attempt. Everything except {@link #PENDING} is final and is never
 * fetched again.
 */
public enum EvidenceStatus {
    /** Collection is incomplete and will be retried at {@code next_attempt_at}. */
    PENDING,
    /** Pod details and every relevant log were collected. */
    COLLECTED,
    /** Pod details were collected, but at least one relevant log is unavailable. */
    PARTIAL,
    /** Tekton never recorded a Pod for this attempt. */
    NO_POD,
    /** The Pod is gone (or its name now belongs to another TaskRun); only TaskRun details are available. */
    POD_DELETED,
    /** The TaskRun is gone (or its name now belongs to another TaskRun). */
    RESOURCE_DELETED,
    /** Access was still denied on the last attempt. */
    FORBIDDEN,
    /** A permanent error, or a transient one that outlasted the last attempt. */
    FAILED;

    public boolean isFinal() {
        return this != PENDING;
    }
}
