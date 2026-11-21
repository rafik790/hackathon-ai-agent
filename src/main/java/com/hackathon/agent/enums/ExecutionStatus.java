package com.hackathon.agent.enums;

/**
 * Normalized execution status for Tekton PipelineRuns and TaskRuns.
 */
public enum ExecutionStatus {
    RUNNING,
    SUCCEEDED,
    FAILED,
    UNKNOWN;

    /** Terminal statuses never transition back to a non-terminal status. */
    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED;
    }
}
