package com.hackathon.agent.evidence.model;

import java.util.List;

/**
 * The parts of a live Tekton TaskRun needed for evidence collection.
 *
 * @param failed   whether the {@code Succeeded} condition is currently {@code False}
 * @param attempts attempts in order: {@code retriesStatus} entries first, the current status last
 */
public record TaskRunDetails(String uid, boolean failed, List<TaskRunAttempt> attempts) {
}
