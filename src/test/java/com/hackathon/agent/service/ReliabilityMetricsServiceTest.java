package com.hackathon.agent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hackathon.agent.entity.PipelineRunEntity;
import com.hackathon.agent.entity.TaskRunEntity;
import com.hackathon.agent.enums.ExecutionStatus;
import com.hackathon.agent.enums.TrendGranularity;
import com.hackathon.agent.repository.ExecutionMetricsRepository;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ReliabilityMetricsServiceTest {

    private static final TimeRange RANGE = new TimeRange(Instant.parse("2026-11-14T00:00:00Z"),
            Instant.parse("2026-11-21T00:00:00Z"), TrendGranularity.DAY);

    @Mock
    private ExecutionMetricsRepository repository;

    @InjectMocks
    private ReliabilityMetricsService service;

    @Test
    void calculatesRatesOverCompletedExecutions() {
        when(repository.countByStatus(eq(PipelineRunEntity.class), any())).thenReturn(counts(7, 3, 2, 1));
        when(repository.countByStatus(eq(TaskRunEntity.class), any())).thenReturn(counts(36, 4, 0, 0));
        when(repository.averageCompletedDuration(eq(PipelineRunEntity.class), any())).thenReturn(Optional.of(1234.6));
        when(repository.countCompletedWithDuration(eq(PipelineRunEntity.class), any())).thenReturn(10L);
        when(repository.completedDurationAtRank(eq(PipelineRunEntity.class), any(), eq(10L))).thenReturn(Optional.of(9000L));

        ReliabilityMetrics metrics = service.calculate(RANGE, "ci", null);

        assertThat(metrics.totalExecutions()).isEqualTo(13);
        assertThat(metrics.successfulExecutions()).isEqualTo(7);
        assertThat(metrics.failedExecutions()).isEqualTo(3);
        assertThat(metrics.runningExecutions()).isEqualTo(2);
        assertThat(metrics.unknownExecutions()).isEqualTo(1);
        assertThat(metrics.successRate()).isEqualTo(70.0);
        assertThat(metrics.failureRate()).isEqualTo(30.0);
        assertThat(metrics.averageDurationMs()).isEqualTo(1235L);
        assertThat(metrics.p95DurationMs()).isEqualTo(9000L);
        assertThat(metrics.totalTaskRuns()).isEqualTo(40);
        assertThat(metrics.failedTaskRuns()).isEqualTo(4);
        assertThat(metrics.taskFailureRate()).isEqualTo(10.0);
        assertThat(metrics.from()).isEqualTo(RANGE.from());
        assertThat(metrics.to()).isEqualTo(RANGE.to());
    }

    @Test
    void emptyDatasetYieldsZeroRatesAndNullDurations() {
        when(repository.countByStatus(any(), any())).thenReturn(counts(0, 0, 0, 0));
        when(repository.averageCompletedDuration(any(), any())).thenReturn(Optional.empty());
        when(repository.countCompletedWithDuration(any(), any())).thenReturn(0L);

        ReliabilityMetrics metrics = service.calculate(RANGE, null, null);

        assertThat(metrics.totalExecutions()).isZero();
        assertThat(metrics.successRate()).isZero();
        assertThat(metrics.failureRate()).isZero();
        assertThat(metrics.taskFailureRate()).isZero();
        assertThat(metrics.averageDurationMs()).isNull();
        assertThat(metrics.p95DurationMs()).isNull();
        verify(repository, never()).completedDurationAtRank(any(), any(), anyLong());
    }

    @Test
    void onlyRunningExecutionsDoNotDivideByZero() {
        when(repository.countByStatus(any(), any())).thenReturn(counts(0, 0, 5, 0));
        when(repository.averageCompletedDuration(any(), any())).thenReturn(Optional.empty());
        when(repository.countCompletedWithDuration(any(), any())).thenReturn(0L);

        ReliabilityMetrics metrics = service.calculate(RANGE, null, null);

        assertThat(metrics.totalExecutions()).isEqualTo(5);
        assertThat(metrics.runningExecutions()).isEqualTo(5);
        assertThat(metrics.successRate()).isZero();
    }

    @Test
    void nearestRankPercentile() {
        assertThat(ReliabilityMetricsService.nearestRank(0.95, 1)).isEqualTo(1);
        assertThat(ReliabilityMetricsService.nearestRank(0.95, 10)).isEqualTo(10);
        assertThat(ReliabilityMetricsService.nearestRank(0.95, 20)).isEqualTo(19);
        assertThat(ReliabilityMetricsService.nearestRank(0.95, 100)).isEqualTo(95);
        assertThat(ReliabilityMetricsService.nearestRank(0.95, 101)).isEqualTo(96);
    }

    @Test
    void percentageIsRoundedToTwoDecimals() {
        assertThat(ReliabilityMetricsService.percentage(1, 3)).isEqualTo(33.33);
        assertThat(ReliabilityMetricsService.percentage(2, 3)).isEqualTo(66.67);
        assertThat(ReliabilityMetricsService.percentage(5, 0)).isZero();
    }

    private static Map<ExecutionStatus, Long> counts(long succeeded, long failed, long running, long unknown) {
        Map<ExecutionStatus, Long> counts = new EnumMap<>(ExecutionStatus.class);
        counts.put(ExecutionStatus.SUCCEEDED, succeeded);
        counts.put(ExecutionStatus.FAILED, failed);
        counts.put(ExecutionStatus.RUNNING, running);
        counts.put(ExecutionStatus.UNKNOWN, unknown);
        return counts;
    }
}
