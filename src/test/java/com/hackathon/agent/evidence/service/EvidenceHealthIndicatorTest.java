package com.hackathon.agent.evidence.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.hackathon.agent.config.EvidenceProperties;
import com.hackathon.agent.config.MonitoringProperties;
import com.hackathon.agent.evidence.model.EvidenceCycleReport;
import com.hackathon.agent.support.EvidenceFixtures;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

class EvidenceHealthIndicatorTest {

    private static final EvidenceProperties DISABLED =
            new EvidenceProperties(false, 30000, 0, 24, 10, 2, 500, 65536, 262144, 14, 5, 60, 1000);

    private final EvidenceStatusTracker tracker = new EvidenceStatusTracker();

    @Test
    void reportsDisabledAndWaiting() {
        assertThat(state(health(DISABLED, true))).isEqualTo("DISABLED");
        assertThat(state(health(EvidenceFixtures.properties(), false))).isEqualTo("DISABLED");
        assertThat(state(health(EvidenceFixtures.properties(), true))).isEqualTo("WAITING");
    }

    @Test
    void missingPermissionsAreVisibleButNeverChangeTheHealthStatus() {
        tracker.record(report(3, 3));

        Health health = health(EvidenceFixtures.properties(), true);

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(state(health)).isEqualTo("DEGRADED");
        assertThat(health.getDetails()).containsEntry("permissionsMissing", true)
                .containsEntry("failedApiCalls", 3).containsEntry("consecutiveFailedCycles", 1);
    }

    @Test
    void recoversAfterASuccessfulCycle() {
        tracker.record(report(1, 0));
        tracker.record(report(0, 0));

        Health health = health(EvidenceFixtures.properties(), true);

        assertThat(state(health)).isEqualTo("OK");
        assertThat(health.getDetails()).containsEntry("totalCycles", 2L).containsEntry("consecutiveFailedCycles", 0);
    }

    private Health health(EvidenceProperties properties, boolean monitoringEnabled) {
        return new EvidenceHealthIndicator(tracker, properties,
                new MonitoringProperties(monitoringEnabled, 15000, 0, List.of("ci"), null, 100, null)).health();
    }

    private static Object state(Health health) {
        return health.getDetails().get("state");
    }

    private static EvidenceCycleReport report(int failedCalls, int forbidden) {
        Instant now = Instant.parse("2026-11-21T12:00:00Z");
        return new EvidenceCycleReport(now, now, 0, 1, 1, 0, 0, 0, failedCalls, forbidden, 0);
    }
}
