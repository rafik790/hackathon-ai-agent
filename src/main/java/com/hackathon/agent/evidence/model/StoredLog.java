package com.hackathon.agent.evidence.model;

/**
 * Outcome of a persisted log, without its text.
 */
public record StoredLog(String containerName, boolean previous, LogStatus status, int storedBytes) {
}
