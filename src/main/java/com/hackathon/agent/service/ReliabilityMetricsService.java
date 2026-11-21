package com.hackathon.agent.service;

import com.hackathon.agent.entity.ExecutionEntity;
import com.hackathon.agent.entity.PipelineRunEntity;
import com.hackathon.agent.entity.TaskRunEntity;
import com.hackathon.agent.enums.ExecutionStatus;
import com.hackathon.agent.repository.ExecutionFilter;
import com.hackathon.agent.repository.ExecutionMetricsRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Calculates reliability metrics from persisted execution history (never from live Kubernetes data).
 * Executions are attributed to a window by their {@code startTime}.
 */
@Service
@Transactional(readOnly = true)
public class ReliabilityMetricsService {

    static final double P95 = 0.95;

    private final ExecutionMetricsRepository metricsRepository;

    public ReliabilityMetricsService(ExecutionMetricsRepository metricsRepository) {
        this.metricsRepository = metricsRepository;
    }

    public ReliabilityMetrics calculate(TimeRange range, String namespace, String pipelineName) {
        ExecutionFilter filter = ExecutionFilter.forWindow(range.from(), range.to(), namespace, pipelineName);

        Map<ExecutionStatus, Long> pipelineCounts = metricsRepository.countByStatus(PipelineRunEntity.class, filter);
        long succeeded = pipelineCounts.get(ExecutionStatus.SUCCEEDED);
        long failed = pipelineCounts.get(ExecutionStatus.FAILED);
        long running = pipelineCounts.get(ExecutionStatus.RUNNING);
        long unknown = pipelineCounts.get(ExecutionStatus.UNKNOWN);
        long total = succeeded + failed + running + unknown;
        long completed = succeeded + failed;

        Long averageDuration = metricsRepository.averageCompletedDuration(PipelineRunEntity.class, filter)
                .map(Math::round)
                .orElse(null);
        Long p95Duration = percentile(PipelineRunEntity.class, filter, P95);

        Map<ExecutionStatus, Long> taskCounts = metricsRepository.countByStatus(TaskRunEntity.class, filter);
        long totalTasks = taskCounts.values().stream().mapToLong(Long::longValue).sum();
        long failedTasks = taskCounts.get(ExecutionStatus.FAILED);
        long completedTasks = taskCounts.get(ExecutionStatus.SUCCEEDED) + failedTasks;

        return new ReliabilityMetrics(
                range.from(), range.to(),
                total, succeeded, failed, running, unknown,
                percentage(succeeded, completed),
                percentage(failed, completed),
                averageDuration,
                p95Duration,
                totalTasks,
                failedTasks,
                percentage(failedTasks, completedTasks));
    }

    /**
     * Nearest-rank percentile of completed execution durations: the value at rank ceil(p * n).
     */
    Long percentile(Class<? extends ExecutionEntity> type, ExecutionFilter filter, double p) {
        long count = metricsRepository.countCompletedWithDuration(type, filter);
        if (count == 0) {
            return null;
        }
        return metricsRepository.completedDurationAtRank(type, filter, nearestRank(p, count)).orElse(null);
    }

    static long nearestRank(double p, long count) {
        return Math.max(1, (long) Math.ceil(p * count));
    }

    /** Percentage rounded to two decimals; 0 when the denominator is 0. */
    static double percentage(long part, long whole) {
        if (whole == 0) {
            return 0.0;
        }
        return BigDecimal.valueOf(part * 100.0 / whole).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }
}
