package com.hackathon.agent.evidence.model;

/**
 * A failed TaskRun recorded by Phase 1 that needs evidence.
 */
public record FailedTaskRun(Long id, String uid, String name, String namespace) {
}
