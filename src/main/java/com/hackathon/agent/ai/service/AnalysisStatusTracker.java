package com.hackathon.agent.ai.service;

import com.hackathon.agent.ai.model.AnalysisCycleReport;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Keeps the outcome of the latest analysis cycle in memory for health reporting.
 */
@Component
public class AnalysisStatusTracker {

    private volatile AnalysisCycleReport lastReport;

    public void record(AnalysisCycleReport report) {
        lastReport = report;
    }

    public Optional<AnalysisCycleReport> lastReport() {
        return Optional.ofNullable(lastReport);
    }
}
