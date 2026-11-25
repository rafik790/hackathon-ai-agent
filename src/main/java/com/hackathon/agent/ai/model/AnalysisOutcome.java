package com.hackathon.agent.ai.model;

/**
 * What happened to one analysis in a cycle.
 */
public enum AnalysisOutcome {
    /** Not processed: taken by another worker, paused, or no quota left. */
    SKIPPED,
    /** Processed and stored. */
    COMPLETED,
    /** Failed, will be tried again. */
    RETRIED,
    /** Failed for good. */
    FAILED,
    /** Processed, but nothing was stored: the claim was lost or the application is stopping. */
    DISCARDED
}
