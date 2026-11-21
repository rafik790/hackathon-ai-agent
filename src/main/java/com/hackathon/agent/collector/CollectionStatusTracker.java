package com.hackathon.agent.collector;

import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Keeps the outcome of recent collection cycles in memory for health and status reporting.
 */
@Component
public class CollectionStatusTracker {

    private volatile CollectionCycleReport lastReport;
    private volatile Instant lastSuccessfulCycleAt;
    private volatile int consecutiveFailedCycles;
    private volatile long totalCycles;

    public synchronized void record(CollectionCycleReport report) {
        lastReport = report;
        totalCycles++;
        if (report.successful()) {
            lastSuccessfulCycleAt = report.finishedAt();
            consecutiveFailedCycles = 0;
        } else {
            consecutiveFailedCycles++;
        }
    }

    public Optional<CollectionCycleReport> lastReport() {
        return Optional.ofNullable(lastReport);
    }

    public Optional<Instant> lastSuccessfulCycleAt() {
        return Optional.ofNullable(lastSuccessfulCycleAt);
    }

    public int consecutiveFailedCycles() {
        return consecutiveFailedCycles;
    }

    public long totalCycles() {
        return totalCycles;
    }
}
