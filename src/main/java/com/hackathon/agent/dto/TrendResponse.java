package com.hackathon.agent.dto;

import com.hackathon.agent.enums.TrendGranularity;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

@Schema(description = "Pipeline execution time series. Buckets are UTC aligned and contiguous (empty buckets have zero counts).")
public record TrendResponse(
        @Schema(example = "2026-11-20T10:00:00Z") Instant from,
        @Schema(example = "2026-11-21T10:00:00Z") Instant to,
        @Schema(example = "HOUR") TrendGranularity granularity,
        List<Point> points) {

    public record Point(
            @Schema(example = "2026-11-21T09:00:00Z") Instant bucketStart,
            @Schema(example = "6") long total,
            @Schema(example = "4") long succeeded,
            @Schema(example = "1") long failed,
            @Schema(example = "1") long running,
            @Schema(example = "0") long unknown,
            @Schema(example = "80.0") double successRate,
            @Schema(example = "171000") Long averageDurationMs) {
    }
}
