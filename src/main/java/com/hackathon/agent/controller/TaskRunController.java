package com.hackathon.agent.controller;

import com.hackathon.agent.dto.ErrorResponse;
import com.hackathon.agent.dto.PageResponse;
import com.hackathon.agent.dto.TaskRunResponse;
import com.hackathon.agent.enums.ExecutionStatus;
import com.hackathon.agent.repository.ExecutionFilter;
import com.hackathon.agent.service.TaskRunQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
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
@RequestMapping("/api/v1/tasks")
@Tag(name = "Task executions", description = "Collected Tekton TaskRuns")
public class TaskRunController {

    private final TaskRunQueryService queryService;

    public TaskRunController(TaskRunQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping
    @Operation(summary = "List TaskRuns", description = "Paginated and filterable; size is capped at 100.")
    @ApiResponse(responseCode = "400", description = "Invalid filter, sort or pagination parameter",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    public PageResponse<TaskRunResponse> list(
            @RequestParam(required = false) String namespace,
            @RequestParam(required = false) String pipelineName,
            @RequestParam(required = false) String pipelineRunName,
            @RequestParam(required = false) String taskName,
            @Parameter(description = "RUNNING, SUCCEEDED, FAILED or UNKNOWN (case-insensitive)")
            @RequestParam(required = false) ExecutionStatus status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @ParameterObject @PageableDefault(size = 20, sort = "startTime", direction = Sort.Direction.DESC)
            Pageable pageable) {
        return queryService.find(new ExecutionFilter(namespace, pipelineName, status, from, to), taskName,
                pipelineRunName, pageable);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a TaskRun")
    @ApiResponse(responseCode = "404", description = "Unknown id",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    public TaskRunResponse get(@PathVariable long id) {
        return queryService.get(id);
    }
}
