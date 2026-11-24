package com.hackathon.agent.evidence.service;

import com.hackathon.agent.config.EvidenceProperties;
import com.hackathon.agent.config.MonitoringProperties;
import com.hackathon.agent.evidence.model.EvidenceCycleReport;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Exposes the evidence worker under {@code /actuator/health} as component {@code evidenceCollector}.
 * <p>
 * The status is always UP and the real state is the {@code state} detail: any other status would also change
 * the overall application health, which must not depend on optional evidence permissions. Only counters are
 * exposed, because health details are publicly readable.
 */
@Component("evidenceCollector")
public class EvidenceHealthIndicator implements HealthIndicator {

    private final EvidenceStatusTracker tracker;
    private final EvidenceProperties properties;
    private final MonitoringProperties monitoring;

    public EvidenceHealthIndicator(EvidenceStatusTracker tracker, EvidenceProperties properties,
                                   MonitoringProperties monitoring) {
        this.tracker = tracker;
        this.properties = properties;
        this.monitoring = monitoring;
    }

    @Override
    public Health health() {
        boolean enabled = properties.enabled() && monitoring.enabled();
        Health.Builder builder = Health.up().withDetail("enabled", enabled);
        EvidenceCycleReport report = tracker.lastReport().orElse(null);
        if (!enabled) {
            return builder.withDetail("state", "DISABLED").build();
        }
        if (report == null) {
            return builder.withDetail("state", "WAITING").build();
        }
        return builder
                .withDetail("state", report.successful() ? "OK" : "DEGRADED")
                .withDetail("permissionsMissing", report.forbiddenApiCalls() > 0)
                .withDetail("totalCycles", tracker.totalCycles())
                .withDetail("consecutiveFailedCycles", tracker.consecutiveFailedCycles())
                .withDetail("lastCycleFinishedAt", report.finishedAt().toString())
                .withDetail("lastCycleDurationMs", report.durationMs())
                .withDetail("taskRunsExamined", report.taskRunsExamined())
                .withDetail("evidenceCreated", report.evidenceCreated())
                .withDetail("evidenceUpdated", report.evidenceUpdated())
                .withDetail("logsFetched", report.logsFetched())
                .withDetail("logsSkipped", report.logsSkipped())
                .withDetail("failedApiCalls", report.failedApiCalls())
                .withDetail("taskRunErrors", report.taskRunErrors())
                .build();
    }
}
