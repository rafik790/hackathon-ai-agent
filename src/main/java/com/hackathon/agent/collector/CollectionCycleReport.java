package com.hackathon.agent.collector;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Summary of one complete collection cycle across all namespaces and collectors.
 *
 * @param discoveredByKind resources returned by Kubernetes per resource kind
 * @param errors           human-readable errors; the cycle continues past individual failures
 */
public record CollectionCycleReport(
        Instant startedAt,
        Instant finishedAt,
        long durationMs,
        Map<String, Integer> discoveredByKind,
        int created,
        int updated,
        int unchanged,
        int skipped,
        List<String> errors) {

    public boolean successful() {
        return errors.isEmpty();
    }
}
