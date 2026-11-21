package com.hackathon.agent.service;

import static com.hackathon.agent.support.TestEntities.NOW;
import static com.hackathon.agent.support.TestEntities.pipelineRun;
import static com.hackathon.agent.support.TestEntities.taskRun;
import static org.assertj.core.api.Assertions.assertThat;

import com.hackathon.agent.entity.PipelineRunEntity;
import com.hackathon.agent.enums.ExecutionStatus;
import com.hackathon.agent.enums.TrendGranularity;
import com.hackathon.agent.repository.ExecutionMetricsRepository;
import com.hackathon.agent.repository.PipelineRunRepository;
import com.hackathon.agent.repository.TaskRunRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * Runs the metric queries against a real (H2, PostgreSQL mode) database populated through Flyway.
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({ExecutionMetricsRepository.class, ReliabilityMetricsService.class, ExecutionTrendService.class})
class MetricsCalculationIntegrationTest {

    private static final TimeRange LAST_24H = new TimeRange(NOW.minus(24, ChronoUnit.HOURS), NOW, TrendGranularity.HOUR);

    @Autowired
    private PipelineRunRepository pipelineRuns;

    @Autowired
    private TaskRunRepository taskRuns;

    @Autowired
    private ReliabilityMetricsService metricsService;

    @Autowired
    private ExecutionTrendService trendService;

    @Test
    void calculatesMetricsFromPersistedHistory() {
        // 20 completed runs with durations 1s..20s: 16 succeeded, 4 failed.
        for (int i = 1; i <= 20; i++) {
            ExecutionStatus status = i % 5 == 0 ? ExecutionStatus.FAILED : ExecutionStatus.SUCCEEDED;
            PipelineRunEntity run = pipelineRuns.save(pipelineRun("pr-" + i, "ci", "build", status,
                    NOW.minus(i, ChronoUnit.HOURS), i * 1000L));
            taskRuns.save(taskRun("tr-" + i, run, "test", status, run.getStartTime(), 500L));
            taskRuns.save(taskRun("tr-clone-" + i, run, "clone", ExecutionStatus.SUCCEEDED, run.getStartTime(), 100L));
        }
        pipelineRuns.save(pipelineRun("pr-running", "ci", "build", ExecutionStatus.RUNNING, NOW.minusSeconds(30), null));
        // Outside the window and in another namespace: must be ignored.
        pipelineRuns.save(pipelineRun("pr-old", "ci", "build", ExecutionStatus.FAILED, NOW.minus(3, ChronoUnit.DAYS), 99_000L));
        pipelineRuns.save(pipelineRun("pr-other", "staging", "deploy", ExecutionStatus.FAILED, NOW.minusSeconds(60), 5L));
        pipelineRuns.flush();

        ReliabilityMetrics metrics = metricsService.calculate(LAST_24H, "ci", null);

        assertThat(metrics.totalExecutions()).isEqualTo(21);
        assertThat(metrics.successfulExecutions()).isEqualTo(16);
        assertThat(metrics.failedExecutions()).isEqualTo(4);
        assertThat(metrics.runningExecutions()).isEqualTo(1);
        assertThat(metrics.successRate()).isEqualTo(80.0);
        assertThat(metrics.failureRate()).isEqualTo(20.0);
        assertThat(metrics.averageDurationMs()).isEqualTo(10_500L);
        assertThat(metrics.p95DurationMs()).isEqualTo(19_000L);
        assertThat(metrics.totalTaskRuns()).isEqualTo(40);
        assertThat(metrics.failedTaskRuns()).isEqualTo(4);
        assertThat(metrics.taskFailureRate()).isEqualTo(10.0);

        ReliabilityMetrics allNamespaces = metricsService.calculate(LAST_24H, null, null);
        assertThat(allNamespaces.totalExecutions()).isEqualTo(22);
    }

    @Test
    void emptyWindowReturnsZeros() {
        ReliabilityMetrics metrics = metricsService.calculate(LAST_24H, null, null);

        assertThat(metrics.totalExecutions()).isZero();
        assertThat(metrics.successRate()).isZero();
        assertThat(metrics.averageDurationMs()).isNull();
        assertThat(metrics.p95DurationMs()).isNull();
    }

    @Test
    void buildsContiguousHourlyTrend() {
        Instant tenHoursAgo = NOW.minus(10, ChronoUnit.HOURS);
        pipelineRuns.save(pipelineRun("a", "ci", "build", ExecutionStatus.SUCCEEDED, tenHoursAgo.plusSeconds(60), 1000L));
        pipelineRuns.save(pipelineRun("b", "ci", "build", ExecutionStatus.FAILED, tenHoursAgo.plusSeconds(120), 3000L));
        pipelineRuns.save(pipelineRun("c", "ci", "build", ExecutionStatus.RUNNING, NOW.minusSeconds(10), null));
        pipelineRuns.flush();

        List<TrendPoint> points = trendService.trend(LAST_24H, null, null);

        assertThat(points).hasSize(24);
        assertThat(points.getFirst().bucketStart()).isEqualTo(NOW.minus(24, ChronoUnit.HOURS));
        TrendPoint busy = points.stream().filter(p -> p.bucketStart().equals(tenHoursAgo)).findFirst().orElseThrow();
        assertThat(busy.total()).isEqualTo(2);
        assertThat(busy.succeeded()).isEqualTo(1);
        assertThat(busy.failed()).isEqualTo(1);
        assertThat(busy.successRate()).isEqualTo(50.0);
        assertThat(busy.averageDurationMs()).isEqualTo(2000L);
        assertThat(points.getLast().running()).isEqualTo(1);
        assertThat(points.stream().mapToLong(TrendPoint::total).sum()).isEqualTo(3);
    }
}
