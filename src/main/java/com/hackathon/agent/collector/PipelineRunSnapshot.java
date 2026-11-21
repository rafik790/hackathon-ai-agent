package com.hackathon.agent.collector;

import java.time.Instant;

/**
 * Normalized view of a Tekton PipelineRun as read from Kubernetes in one collection cycle.
 */
public record PipelineRunSnapshot(
        String uid,
        String name,
        String namespace,
        String pipelineName,
        Instant resourceCreatedAt,
        ExecutionState state) {
}
