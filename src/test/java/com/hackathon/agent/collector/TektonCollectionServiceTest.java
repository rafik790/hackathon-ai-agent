package com.hackathon.agent.collector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hackathon.agent.config.MonitoringProperties;
import com.hackathon.agent.kubernetes.KubernetesAccessException;
import com.hackathon.agent.service.UpsertResult;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

class TektonCollectionServiceTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-11-21T12:00:00Z"), ZoneOffset.UTC);
    private final MonitoringProperties properties =
            new MonitoringProperties(true, 15000, 0, List.of("ci", "staging"), null, 100, null);
    private final ResourceCollector pipelineCollector = mock(ResourceCollector.class);
    private final ResourceCollector taskCollector = mock(ResourceCollector.class);
    private final CollectionStatusTracker tracker = new CollectionStatusTracker();
    private final TektonCollectionService service =
            new TektonCollectionService(List.of(pipelineCollector, taskCollector), properties, tracker, clock);

    @Test
    void kubernetesFailureInOneNamespaceDoesNotStopTheCycle() {
        stubKinds();
        when(pipelineCollector.collect("ci")).thenThrow(new KubernetesAccessException("connection refused", null));
        when(taskCollector.collect("ci")).thenReturn(result("TaskRun", "ci", 2, new UpsertResult(2, 0, 0)));
        when(pipelineCollector.collect("staging")).thenReturn(result("PipelineRun", "staging", 1, new UpsertResult(0, 1, 0)));
        when(taskCollector.collect("staging")).thenReturn(result("TaskRun", "staging", 3, new UpsertResult(0, 0, 3)));

        CollectionCycleReport report = service.runCycle().orElseThrow();

        assertThat(report.successful()).isFalse();
        assertThat(report.errors()).singleElement().asString().contains("PipelineRun/ci").contains("connection refused");
        assertThat(report.discoveredByKind()).containsEntry("PipelineRun", 1).containsEntry("TaskRun", 5);
        assertThat(report.created()).isEqualTo(2);
        assertThat(report.updated()).isEqualTo(1);
        assertThat(report.unchanged()).isEqualTo(3);
        verify(taskCollector, times(1)).collect("staging");
        assertThat(tracker.consecutiveFailedCycles()).isEqualTo(1);
    }

    @Test
    void databaseAndUnexpectedErrorsAreReportedNotThrown() {
        stubKinds();
        when(pipelineCollector.collect("ci")).thenThrow(new DataAccessResourceFailureException("db down"));
        when(taskCollector.collect("ci")).thenThrow(new IllegalStateException("boom"));
        when(pipelineCollector.collect("staging")).thenReturn(result("PipelineRun", "staging", 0, UpsertResult.EMPTY));
        when(taskCollector.collect("staging")).thenReturn(result("TaskRun", "staging", 0, UpsertResult.EMPTY));

        CollectionCycleReport report = service.runCycle().orElseThrow();

        assertThat(report.errors()).hasSize(2);
        assertThat(report.errors().get(0)).contains("database error");
    }

    @Test
    void successfulCycleResetsFailureCounter() {
        stubKinds();
        when(pipelineCollector.collect("ci")).thenThrow(new KubernetesAccessException("timeout", null))
                .thenReturn(result("PipelineRun", "ci", 0, UpsertResult.EMPTY));
        when(taskCollector.collect("ci")).thenReturn(result("TaskRun", "ci", 0, UpsertResult.EMPTY));
        when(pipelineCollector.collect("staging")).thenReturn(result("PipelineRun", "staging", 0, UpsertResult.EMPTY));
        when(taskCollector.collect("staging")).thenReturn(result("TaskRun", "staging", 0, UpsertResult.EMPTY));

        service.runCycle();
        service.runCycle();

        assertThat(tracker.consecutiveFailedCycles()).isZero();
        assertThat(tracker.totalCycles()).isEqualTo(2);
        assertThat(tracker.lastSuccessfulCycleAt()).contains(clock.instant());
    }

    @Test
    void overlappingCycleIsSkipped() throws Exception {
        stubKinds();
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(pipelineCollector.collect("ci")).thenAnswer(invocation -> {
            inside.countDown();
            release.await(5, TimeUnit.SECONDS);
            return result("PipelineRun", "ci", 0, UpsertResult.EMPTY);
        });
        when(taskCollector.collect("ci")).thenReturn(result("TaskRun", "ci", 0, UpsertResult.EMPTY));
        when(pipelineCollector.collect("staging")).thenReturn(result("PipelineRun", "staging", 0, UpsertResult.EMPTY));
        when(taskCollector.collect("staging")).thenReturn(result("TaskRun", "staging", 0, UpsertResult.EMPTY));

        AtomicReference<Optional<CollectionCycleReport>> firstResult = new AtomicReference<>();
        Thread first = Thread.ofVirtual().start(() -> firstResult.set(service.runCycle()));
        assertThat(inside.await(5, TimeUnit.SECONDS)).isTrue();

        Optional<CollectionCycleReport> overlapping = service.runCycle();
        release.countDown();
        first.join(5000);

        assertThat(overlapping).isEmpty();
        assertThat(firstResult.get()).isPresent();
        assertThat(tracker.totalCycles()).isEqualTo(1);
    }

    private void stubKinds() {
        when(pipelineCollector.resourceKind()).thenReturn("PipelineRun");
        when(taskCollector.resourceKind()).thenReturn("TaskRun");
    }

    private static CollectionResult result(String kind, String ns, int discovered, UpsertResult upsert) {
        return new CollectionResult(kind, ns, discovered, 0, upsert);
    }
}
