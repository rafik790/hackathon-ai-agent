package com.hackathon.agent.controller;

import com.hackathon.agent.dto.DashboardSummaryResponse;
import com.hackathon.agent.dto.ErrorResponse;
import com.hackathon.agent.dto.TrendResponse;
import com.hackathon.agent.enums.TrendGranularity;
import com.hackathon.agent.service.DashboardService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/dashboard")
@Tag(name = "Dashboard", description = "Reliability metrics and execution trends")
public class DashboardController {

    private static final String SUMMARY_EXAMPLE = """
            {
              "from": "2026-11-14T10:00:00Z",
              "to": "2026-11-21T10:00:00Z",
              "totalExecutions": 120,
              "successfulExecutions": 102,
              "failedExecutions": 15,
              "runningExecutions": 3,
              "unknownExecutions": 0,
              "successRate": 87.18,
              "failureRate": 12.82,
              "averageDurationMs": 184250,
              "p95DurationMs": 402000,
              "totalTaskRuns": 560,
              "failedTaskRuns": 18,
              "taskFailureRate": 3.23
            }""";

    private static final String TREND_EXAMPLE = """
            {
              "from": "2026-11-20T10:00:00Z",
              "to": "2026-11-21T10:00:00Z",
              "granularity": "HOUR",
              "points": [
                {"bucketStart": "2026-11-20T10:00:00Z", "total": 0, "succeeded": 0, "failed": 0, "running": 0,
                 "unknown": 0, "successRate": 0.0, "averageDurationMs": null},
                {"bucketStart": "2026-11-20T11:00:00Z", "total": 5, "succeeded": 4, "failed": 1, "running": 0,
                 "unknown": 0, "successRate": 80.0, "averageDurationMs": 171000}
              ]
            }""";

    private final DashboardService dashboardService;

    public DashboardController(DashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    @GetMapping("/summary")
    @Operation(summary = "Reliability summary",
            description = "Pipeline and task reliability metrics for a preset (range) or custom (from/to) window.")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = DashboardSummaryResponse.class),
            examples = @ExampleObject(name = "summary", value = SUMMARY_EXAMPLE)))
    @ApiResponse(responseCode = "400", description = "Invalid time range",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    public DashboardSummaryResponse summary(
            @Parameter(description = "Preset window: 24h, 7d or 30d (default 7d). Not combinable with from/to.")
            @RequestParam(required = false) String range,
            @Parameter(description = "Custom window start (ISO-8601, inclusive)", example = "2026-11-01T00:00:00Z")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @Parameter(description = "Custom window end (ISO-8601, exclusive, default now)")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) String namespace,
            @RequestParam(required = false) String pipelineName) {
        return dashboardService.summary(range, from, to, namespace, pipelineName);
    }

    @GetMapping("/trends")
    @Operation(summary = "Execution trends",
            description = "Pipeline executions grouped by hour (windows up to 48h) or day, unless granularity is given.")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = TrendResponse.class),
            examples = @ExampleObject(name = "trend", value = TREND_EXAMPLE)))
    @ApiResponse(responseCode = "400", description = "Invalid time range or granularity",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    public TrendResponse trends(
            @Parameter(description = "Preset window: 24h, 7d or 30d (default 7d)")
            @RequestParam(required = false) String range,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @Parameter(description = "hour or day; derived from the window size when omitted")
            @RequestParam(required = false) TrendGranularity granularity,
            @RequestParam(required = false) String namespace,
            @RequestParam(required = false) String pipelineName) {
        return dashboardService.trends(range, from, to, granularity, namespace, pipelineName);
    }
}
