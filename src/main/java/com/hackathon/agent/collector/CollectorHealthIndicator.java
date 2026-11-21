package com.hackathon.agent.collector;

import com.hackathon.agent.config.MonitoringProperties;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.Status;
import org.springframework.stereotype.Component;

/**
 * Exposes the collector state under {@code /actuator/health} as component {@code tektonCollector}.
 * <p>
 * A failing collection cycle reports {@code DEGRADED} (HTTP 200, see application.yaml) rather than
 * DOWN: the REST API keeps serving persisted history when the cluster is temporarily unreachable,
 * so the pod must not be restarted or removed from service because of it.
 */
@Component("tektonCollector")
public class CollectorHealthIndicator implements HealthIndicator {

    public static final Status DEGRADED = new Status("DEGRADED", "Last collection cycle reported errors");

    private final CollectionStatusTracker tracker;
    private final MonitoringProperties properties;

    public CollectorHealthIndicator(CollectionStatusTracker tracker, MonitoringProperties properties) {
        this.tracker = tracker;
        this.properties = properties;
    }

    @Override
    public Health health() {
        if (!properties.enabled()) {
            return Health.up().withDetail("enabled", false).build();
        }
        return tracker.lastReport()
                .map(report -> {
                    Health.Builder builder = report.successful() ? Health.up() : Health.status(DEGRADED);
                    builder.withDetail("enabled", true)
                            .withDetail("namespaces", properties.namespaces())
                            .withDetail("lastCycleFinishedAt", report.finishedAt().toString())
                            .withDetail("lastCycleDurationMs", report.durationMs())
                            .withDetail("consecutiveFailedCycles", tracker.consecutiveFailedCycles());
                    tracker.lastSuccessfulCycleAt()
                            .ifPresent(at -> builder.withDetail("lastSuccessfulCycleAt", at.toString()));
                    if (!report.successful()) {
                        builder.withDetail("errors", report.errors());
                    }
                    return builder.build();
                })
                .orElseGet(() -> Health.unknown()
                        .withDetail("enabled", true)
                        .withDetail("namespaces", properties.namespaces())
                        .withDetail("message", "No collection cycle completed yet")
                        .build());
    }
}
