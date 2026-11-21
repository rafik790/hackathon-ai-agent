package com.hackathon.agent.controller;

import com.hackathon.agent.dto.ErrorResponse;
import com.hackathon.agent.dto.PageResponse;
import com.hackathon.agent.dto.PipelineRunResponse;
import com.hackathon.agent.dto.TaskRunResponse;
import com.hackathon.agent.enums.ExecutionStatus;
import com.hackathon.agent.repository.ExecutionFilter;
import com.hackathon.agent.service.PipelineRunQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.List;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/pipelines")
@Tag(name = "Pipeline executions", description = "Collected Tekton PipelineRuns")
public class PipelineRunController {

    private static final String PAGE_EXAMPLE = """
            {
              "content": [{
                "id": 42,
                "pipelineRunUid": "6f1c2c9e-3f5b-4c1e-9a43-1b1f0f6f7a10",
                "pipelineRunName": "demo-app-build-x7k2p",
                "namespace": "ci",
                "pipelineName": "demo-app-build",
                "status": "FAILED",
                "startTime": "2026-11-21T09:58:02Z",
                "completionTime": "2026-11-21T10:01:17Z",
                "durationMs": 195000,
                "failureReason": "Failed",
                "failureMessage": "Tasks Completed: 3 (Failed: 1, Cancelled 0), Skipped: 1",
                "resourceCreatedAt": "2026-11-21T09:58:01Z",
                "createdAt": "2026-11-21T09:58:10Z",
                "updatedAt": "2026-11-21T10:01:25Z"
              }],
              "page": 0, "size": 20, "totalElements": 1, "totalPages": 1,
              "first": true, "last": true, "sort": "startTime: DESC"
            }""";

    private final PipelineRunQueryService queryService;

    public PipelineRunController(PipelineRunQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping
    @Operation(summary = "List PipelineRuns",
            description = "Paginated and filterable. Sort with e.g. sort=startTime,desc; size is capped at 100.")
    @ApiResponse(responseCode = "200", content = @Content(examples = @ExampleObject(name = "page", value = PAGE_EXAMPLE)))
    @ApiResponse(responseCode = "400", description = "Invalid filter, sort or pagination parameter",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    public PageResponse<PipelineRunResponse> list(
            @RequestParam(required = false) String namespace,
            @RequestParam(required = false) String pipelineName,
            @Parameter(description = "RUNNING, SUCCEEDED, FAILED or UNKNOWN (case-insensitive)")
            @RequestParam(required = false) ExecutionStatus status,
            @Parameter(description = "Start time lower bound (inclusive, ISO-8601)")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @Parameter(description = "Start time upper bound (exclusive, ISO-8601)")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @ParameterObject @PageableDefault(size = 20, sort = "startTime", direction = Sort.Direction.DESC)
            Pageable pageable) {
        return queryService.find(new ExecutionFilter(namespace, pipelineName, status, from, to), pageable);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a PipelineRun")
    @ApiResponse(responseCode = "404", description = "Unknown id",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    public PipelineRunResponse get(@PathVariable long id) {
        return queryService.get(id);
    }

    @GetMapping("/{id}/tasks")
    @Operation(summary = "TaskRuns of a PipelineRun", description = "Ordered by start time.")
    @ApiResponse(responseCode = "404", description = "Unknown id",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    public List<TaskRunResponse> tasks(@PathVariable long id) {
        return queryService.tasksOf(id);
    }
}
