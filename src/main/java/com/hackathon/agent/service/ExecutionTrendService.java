package com.hackathon.agent.service;

import com.hackathon.agent.entity.PipelineRunEntity;
import com.hackathon.agent.enums.TrendGranularity;
import com.hackathon.agent.repository.ExecutionFilter;
import com.hackathon.agent.repository.ExecutionMetricsRepository;
import com.hackathon.agent.repository.ExecutionSample;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Builds a gap-free time series of pipeline executions grouped by hour or day (UTC).
 * Bucketing is done in Java on a small projection to stay database-agnostic.
 */
@Service
@Transactional(readOnly = true)
public class ExecutionTrendService {

    private final ExecutionMetricsRepository metricsRepository;

    public ExecutionTrendService(ExecutionMetricsRepository metricsRepository) {
        this.metricsRepository = metricsRepository;
    }

    public List<TrendPoint> trend(TimeRange range, String namespace, String pipelineName) {
        TrendGranularity granularity = range.granularity();
        Map<Instant, Bucket> buckets = new LinkedHashMap<>();
        for (Instant bucket = granularity.truncate(range.from()); bucket.isBefore(range.to());
             bucket = bucket.plus(granularity.step())) {
            buckets.put(bucket, new Bucket());
        }

        ExecutionFilter filter = ExecutionFilter.forWindow(range.from(), range.to(), namespace, pipelineName);
        for (ExecutionSample sample : metricsRepository.findSamples(PipelineRunEntity.class, filter)) {
            Bucket bucket = buckets.get(granularity.truncate(sample.startTime()));
            if (bucket != null) {
                bucket.add(sample);
            }
        }

        List<TrendPoint> points = new ArrayList<>(buckets.size());
        buckets.forEach((start, bucket) -> points.add(bucket.toPoint(start)));
        return points;
    }

    private static final class Bucket {
        private long succeeded;
        private long failed;
        private long running;
        private long unknown;
        private long durationSum;
        private long durationCount;

        void add(ExecutionSample sample) {
            switch (sample.status()) {
                case SUCCEEDED -> succeeded++;
                case FAILED -> failed++;
                case RUNNING -> running++;
                case UNKNOWN -> unknown++;
            }
            if (sample.status().isTerminal() && sample.durationMs() != null) {
                durationSum += sample.durationMs();
                durationCount++;
            }
        }

        TrendPoint toPoint(Instant start) {
            return new TrendPoint(start,
                    succeeded + failed + running + unknown,
                    succeeded, failed, running, unknown,
                    ReliabilityMetricsService.percentage(succeeded, succeeded + failed),
                    durationCount == 0 ? null : Math.round((double) durationSum / durationCount));
        }
    }
}
