package com.hackathon.agent.resolution.model;

/** The kind of run a resolution record belongs to. */
public enum ResolutionTargetType {
    TASK_RUN,
    PIPELINE_RUN;

    /** Unique key of the record of a run; it stays the same when the run row is deleted. */
    public String key(long runId) {
        return name() + ":" + runId;
    }
}
