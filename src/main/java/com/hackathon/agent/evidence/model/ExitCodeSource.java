package com.hackathon.agent.evidence.model;

/**
 * Where an exit code came from.
 */
public enum ExitCodeSource {
    /** Reported in the Pod status. */
    POD,
    /** Reported by Tekton in the TaskRun status. */
    TASKRUN,
    /** Filled in by Tekton for a step whose Pod it terminated; the container never exited by itself. */
    SYNTHETIC
}
