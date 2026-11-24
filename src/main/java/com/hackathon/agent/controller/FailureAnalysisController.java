package com.hackathon.agent.controller;

import com.hackathon.agent.ai.model.AnalysisStatus;
import com.hackathon.agent.ai.model.FailureCategory;
import com.hackathon.agent.ai.model.Severity;
import com.hackathon.agent.dto.ErrorResponse;
import com.hackathon.agent.dto.FailureAnalysisResponse;
import com.hackathon.agent.dto.FailureAnalysisSummaryResponse;
import com.hackathon.agent.dto.PageResponse;
import com.hackathon.agent.service.FailureAnalysisQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Failure analysis", description = "AI-generated explanations of failed TaskRuns")
public class FailureAnalysisController {

    private final FailureAnalysisQueryService service;

    public FailureAnalysisController(FailureAnalysisQueryService service) {
        this.service = service;
    }

    @GetMapping("/failure-analyses")
    @Operation(summary = "List failure analyses", description = "Paginated and filterable; size is capped at 100.")
    @ApiResponse(responseCode = "400", description = "Invalid filter, sort or pagination parameter",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    public PageResponse<FailureAnalysisSummaryResponse> list(
            @Parameter(description = "Failure category (case-insensitive)")
            @RequestParam(required = false) FailureCategory category,
            @Parameter(description = "CRITICAL, HIGH, MEDIUM or LOW (case-insensitive)")
            @RequestParam(required = false) Severity severity,
            @Parameter(description = "PENDING, PROCESSING, COMPLETED, RETRY_PENDING or FAILED (case-insensitive)")
            @RequestParam(required = false) AnalysisStatus status,
            @ParameterObject @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC)
            Pageable pageable) {
        return service.find(category, severity, status, pageable);
    }

    @GetMapping("/failure-analyses/{id}")
    @Operation(summary = "Get a failure analysis")
    @ApiResponse(responseCode = "404", description = "Unknown id",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    public FailureAnalysisResponse get(@PathVariable long id) {
        return service.get(id);
    }

    @GetMapping("/tasks/{taskRunId}/analysis")
    @Operation(summary = "Analysis of a TaskRun", description = "The newest analysis of the last attempt.")
    @ApiResponse(responseCode = "404", description = "Unknown TaskRun or no analysis yet",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    public FailureAnalysisResponse ofTaskRun(@PathVariable long taskRunId) {
        return service.latestOfTaskRun(taskRunId);
    }

    @PostMapping("/failure-evidence/{evidenceId}/analyse")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "Request an analysis", description = "Queues an analysis and returns it; the scheduler "
            + "processes it within the usual limits. An analysis already in progress is returned instead of "
            + "starting another one.")
    @ApiResponse(responseCode = "404", description = "Unknown evidence",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "409", description = "AI analysis is disabled, evidence collection is not finished, "
            + "or the evidence was already analysed",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    public FailureAnalysisResponse analyse(
            @PathVariable long evidenceId,
            @Parameter(description = "Analyse again although a completed analysis of the current version exists")
            @RequestParam(defaultValue = "false") boolean force) {
        return service.request(evidenceId, force);
    }
}
