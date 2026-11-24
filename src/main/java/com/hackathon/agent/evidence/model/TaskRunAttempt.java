package com.hackathon.agent.evidence.model;

import java.util.List;

/**
 * One execution attempt of a TaskRun.
 *
 * @param attempt    zero-based; equals the number of earlier retries
 * @param podName    {@code status.podName}, null when no Pod was recorded
 * @param reason     reason of the {@code Succeeded} condition
 * @param containers steps and sidecars as reported by Tekton
 */
public record TaskRunAttempt(int attempt, String podName, String reason, List<ContainerSnapshot> containers) {
}
