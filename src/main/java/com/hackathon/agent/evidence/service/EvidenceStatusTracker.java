package com.hackathon.agent.evidence.service;

import com.hackathon.agent.evidence.model.EvidenceCycleReport;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Keeps the outcome of the latest evidence cycle in memory for health reporting.
 */
@Component
public class EvidenceStatusTracker {

    private volatile EvidenceCycleReport lastReport;
    private volatile long totalCycles;
    private volatile int consecutiveFailedCycles;

    public synchronized void record(EvidenceCycleReport report) {
        lastReport = report;
        totalCycles++;
        consecutiveFailedCycles = report.successful() ? 0 : consecutiveFailedCycles + 1;
    }

    public Optional<EvidenceCycleReport> lastReport() {
        return Optional.ofNullable(lastReport);
    }

    public long totalCycles() {
        return totalCycles;
    }

    public int consecutiveFailedCycles() {
        return consecutiveFailedCycles;
    }
}
