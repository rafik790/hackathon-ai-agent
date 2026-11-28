package com.hackathon.agent.mapper;

import com.hackathon.agent.dto.DashboardSummaryResponse;
import com.hackathon.agent.dto.PipelineRunResponse;
import com.hackathon.agent.dto.TaskRunResponse;
import com.hackathon.agent.dto.TrendResponse;
import com.hackathon.agent.entity.PipelineRunEntity;
import com.hackathon.agent.entity.TaskRunEntity;
import com.hackathon.agent.resolution.model.ResolutionStatus;
import com.hackathon.agent.service.ReliabilityMetrics;
import com.hackathon.agent.service.TimeRange;
import com.hackathon.agent.service.TrendPoint;
import java.util.List;
import java.util.Map;

/**
 * Converts persistence entities and service results into API DTOs. Entities never leave the service layer.
 */
public final class ExecutionMapper {

    private ExecutionMapper() {
    }

    public static PipelineRunResponse toResponse(PipelineRunEntity e) {
        return new PipelineRunResponse(e.getId(), e.getPipelineRunUid(), e.getPipelineRunName(), e.getNamespace(),
                e.getPipelineName(), e.getStatus(), e.getStartTime(), e.getCompletionTime(), e.getDurationMs(),
                e.getFailureReason(), e.getFailureMessage(), e.getResourceCreatedAt(), e.getCreatedAt(),
                e.getUpdatedAt());
    }

    public static TaskRunResponse toResponse(TaskRunEntity e) {
        return new TaskRunResponse(e.getId(), e.getTaskRunUid(), e.getTaskRunName(), e.getNamespace(),
                e.getPipelineRunUid(), e.getPipelineRunName(), e.getPipelineName(), e.getTaskName(), e.getStatus(),
                e.getStartTime(), e.getCompletionTime(), e.getDurationMs(), e.getFailureReason(),
                e.getFailureMessage(), e.getResourceCreatedAt(), e.getCreatedAt(), e.getUpdatedAt());
    }

    public static DashboardSummaryResponse toResponse(ReliabilityMetrics m) {
        return new DashboardSummaryResponse(m.from(), m.to(), m.totalExecutions(), m.successfulExecutions(),
                m.failedExecutions(), m.runningExecutions(), m.unknownExecutions(), m.successRate(),
                m.failureRate(), m.averageDurationMs(), m.p95DurationMs(), m.totalTaskRuns(), m.failedTaskRuns(),
                m.taskFailureRate());
    }

    /** @param resolutionCounts the failed TaskRuns of the same window by resolution status */
    public static DashboardSummaryResponse toResponse(ReliabilityMetrics m,
                                                      Map<ResolutionStatus, Long> resolutionCounts) {
        return new DashboardSummaryResponse(m.from(), m.to(), m.totalExecutions(), m.successfulExecutions(),
                m.failedExecutions(), m.runningExecutions(), m.unknownExecutions(), m.successRate(),
                m.failureRate(), m.averageDurationMs(), m.p95DurationMs(), m.totalTaskRuns(), m.failedTaskRuns(),
                m.taskFailureRate(), resolutionCounts);
    }

    public static TrendResponse toResponse(TimeRange range, List<TrendPoint> points) {
        return new TrendResponse(range.from(), range.to(), range.granularity(), points.stream()
                .map(p -> new TrendResponse.Point(p.bucketStart(), p.total(), p.succeeded(), p.failed(), p.running(),
                        p.unknown(), p.successRate(), p.averageDurationMs()))
                .toList());
    }
}
