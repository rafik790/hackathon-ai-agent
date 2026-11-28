package com.hackathon.agent.resolution.model;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Where the work on a failure stands. Not the Tekton execution status, and not the status of an AI analysis or
 * investigation: an investigation can be COMPLETED while the fix is still in progress.
 */
public enum ResolutionStatus {
    /** Failure detected; no resolution work has started. Also the status of a failed run without a record. */
    OPEN,
    /** Someone is actively investigating the failure. */
    INVESTIGATING,
    /** A corrective action is being implemented. */
    FIX_IN_PROGRESS,
    /** The issue has been corrected and verified. */
    RESOLVED,
    /** Marked resolved, but the failure occurred again or the fix did not work. */
    REOPENED;

    /**
     * The allowed changes. There is no way back to OPEN: once work has started, a failure that needs more work is
     * REOPENED or goes back to INVESTIGATING, which keeps the history meaningful.
     */
    private static final Map<ResolutionStatus, Set<ResolutionStatus>> TRANSITIONS = Map.of(
            OPEN, EnumSet.of(INVESTIGATING, FIX_IN_PROGRESS),
            INVESTIGATING, EnumSet.of(FIX_IN_PROGRESS, RESOLVED),
            FIX_IN_PROGRESS, EnumSet.of(RESOLVED),
            RESOLVED, EnumSet.of(REOPENED),
            REOPENED, EnumSet.of(INVESTIGATING, FIX_IN_PROGRESS));

    /** Statuses this one may change to; keeping the same status (updating details only) is always allowed. */
    public Set<ResolutionStatus> nextStatuses() {
        return EnumSet.copyOf(TRANSITIONS.get(this));
    }

    public boolean canChangeTo(ResolutionStatus next) {
        return next == this || TRANSITIONS.get(this).contains(next);
    }
}
