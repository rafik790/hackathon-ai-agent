package com.hackathon.agent.collector;

import java.time.Instant;

/**
 * Normalized view of a Tekton TaskRun as read from Kubernetes in one collection cycle.
 */
public record TaskRunSnapshot(
        String uid,
        String name,
        String namespace,
        String pipelineRunUid,
        String pipelineRunName,
        String pipelineName,
        String taskName,
        Instant resourceCreatedAt,
        ExecutionState state) {
}
