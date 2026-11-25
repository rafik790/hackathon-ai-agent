package com.hackathon.agent.ai.service;

import com.hackathon.agent.ai.model.AnalysisCycleReport;
import com.hackathon.agent.config.AiProperties;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Exposes AI analysis under {@code /actuator/health} as component {@code aiAnalysis}. Always UP, like the evidence
 * worker, because optional features must not change the overall health; see the {@code state} detail.
 */
@Component("aiAnalysis")
public class AiHealthIndicator implements HealthIndicator {

    private final AnalysisStatusTracker tracker;
    private final AiProperties properties;

    public AiHealthIndicator(AnalysisStatusTracker tracker, AiProperties properties) {
        this.tracker = tracker;
        this.properties = properties;
    }

    @Override
    public Health health() {
        Health.Builder builder = Health.up().withDetail("enabled", properties.enabled());
        if (!properties.enabled()) {
            return builder.withDetail("state", "DISABLED").build();
        }
        builder.withDetail("provider", properties.provider());
        AnalysisCycleReport report = tracker.lastReport().orElse(null);
        if (report == null) {
            return builder.withDetail("state", "WAITING").build();
        }
        String state = report.paused() ? "PAUSED"
                : report.quotaExhausted() ? "QUOTA_EXHAUSTED"
                : report.failed() + report.retried() > 0 ? "DEGRADED" : "OK";
        return builder.withDetail("state", state)
                .withDetail("lastCycleFinishedAt", report.finishedAt().toString())
                .withDetail("lastCycleDurationMs", report.durationMs())
                .withDetail("processed", report.processed())
                .withDetail("completed", report.completed())
                .withDetail("retried", report.retried())
                .withDetail("failed", report.failed())
                .build();
    }
}
