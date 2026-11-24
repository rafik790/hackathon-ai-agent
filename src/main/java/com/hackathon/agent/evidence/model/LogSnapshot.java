package com.hackathon.agent.evidence.model;

import java.time.Instant;

/**
 * Result for one log stream of a container. {@code log} is only present for {@link LogStatus#COLLECTED}.
 */
public record LogSnapshot(String containerName, boolean previous, LogStatus status, SanitizedLog log,
                          Instant fetchedAt) {
}
