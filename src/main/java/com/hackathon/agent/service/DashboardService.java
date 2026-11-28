package com.hackathon.agent.service;

import com.hackathon.agent.dto.DashboardSummaryResponse;
import com.hackathon.agent.dto.TrendResponse;
import com.hackathon.agent.enums.TrendGranularity;
import com.hackathon.agent.mapper.ExecutionMapper;
import com.hackathon.agent.resolution.repository.ResolutionCountRepository;
import java.time.Instant;
import org.springframework.stereotype.Service;

/**
 * Facade used by the dashboard controller: resolves the time window and delegates to the metrics services. The summary
 * also counts the window's failed TaskRuns by resolution status, read-only and with the same filters.
 */
@Service
public class DashboardService {

    private final TimeRangeResolver timeRangeResolver;
    private final ReliabilityMetricsService metricsService;
    private final ExecutionTrendService trendService;
    private final ResolutionCountRepository resolutionCounts;

    public DashboardService(TimeRangeResolver timeRangeResolver, ReliabilityMetricsService metricsService,
                            ExecutionTrendService trendService, ResolutionCountRepository resolutionCounts) {
        this.resolutionCounts = resolutionCounts;
        this.timeRangeResolver = timeRangeResolver;
        this.metricsService = metricsService;
        this.trendService = trendService;
    }

    public DashboardSummaryResponse summary(String range, Instant from, Instant to, String namespace,
                                            String pipelineName) {
        TimeRange timeRange = timeRangeResolver.resolve(range, from, to, null);
        return ExecutionMapper.toResponse(metricsService.calculate(timeRange, namespace, pipelineName),
                resolutionCounts.failedTaskRunsByResolution(timeRange.from(), timeRange.to(), namespace, pipelineName));
    }

    public TrendResponse trends(String range, Instant from, Instant to, TrendGranularity granularity,
                                String namespace, String pipelineName) {
        TimeRange timeRange = timeRangeResolver.resolve(range, from, to, granularity);
        return ExecutionMapper.toResponse(timeRange, trendService.trend(timeRange, namespace, pipelineName));
    }
}
