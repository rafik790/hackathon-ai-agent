package com.hackathon.agent.investigation.api;

import com.hackathon.agent.dto.ErrorResponse;
import com.hackathon.agent.dto.PageResponse;
import com.hackathon.agent.exception.ConflictException;
import com.hackathon.agent.investigation.api.AgentInvestigationDtos.InvestigationResponse;
import com.hackathon.agent.investigation.api.AgentInvestigationDtos.InvestigationStepResponse;
import com.hackathon.agent.investigation.api.AgentInvestigationDtos.StartInvestigationRequest;
import com.hackathon.agent.investigation.model.InvestigationModel.InvestigationStatus;
import com.hackathon.agent.investigation.service.AgentInvestigationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Agent investigations. Always registered, so that clients get a clear answer while the feature is disabled.
 */
@RestController
@RequestMapping("/api/v1/agent-investigations")
@Tag(name = "Agent investigation", description = "Read-only AI agent that investigates failed PipelineRuns and TaskRuns with Kubernetes and Tekton tools")
public class AgentInvestigationController {

    static final String DISABLED = "Agent investigation is disabled; set AGENT_INVESTIGATION_ENABLED=true to use it";

    private final ObjectProvider<AgentInvestigationService> service;

    public AgentInvestigationController(ObjectProvider<AgentInvestigationService> service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "Start an investigation", description = "Queues an investigation of a failed TaskRun or PipelineRun "
            + "and returns it; it runs in the background. An investigation already queued or running for the run is returned instead.")
    @ApiResponse(responseCode = "400", description = "Missing, ambiguous or invalid run reference, or namespace not monitored",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "404", description = "Run not found",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "409", description = "Feature or AI disabled, or the run did not fail",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "429", description = "The daily AI quota is exhausted",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    public InvestigationResponse start(@RequestBody StartInvestigationRequest request) {
        return service().start(request);
    }

    @GetMapping("/{investigationId}")
    @Operation(summary = "Get an investigation", description = "Status, counters and, when completed, the report.")
    @ApiResponse(responseCode = "404", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    public InvestigationResponse get(@PathVariable long investigationId) {
        return service().get(investigationId);
    }

    @GetMapping("/{investigationId}/steps")
    @Operation(summary = "Investigation timeline", description = "Every step in order: the tool the agent chose, its sanitized arguments and result.")
    @ApiResponse(responseCode = "404", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    public List<InvestigationStepResponse> steps(@PathVariable long investigationId) {
        return service().steps(investigationId);
    }

    @GetMapping
    @Operation(summary = "List investigations", description = "Paginated; filters are exact matches.")
    public PageResponse<InvestigationResponse> list(
            @RequestParam(required = false) String taskRunName,
            @RequestParam(required = false) String pipelineRunName,
            @RequestParam(required = false) String namespace,
            @Parameter(description = "PENDING, RUNNING, COMPLETED or FAILED") @RequestParam(required = false) InvestigationStatus status,
            @ParameterObject @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return service().list(taskRunName, pipelineRunName, namespace, status, pageable);
    }

    private AgentInvestigationService service() {
        AgentInvestigationService available = service.getIfAvailable();
        if (available == null) {
            throw new ConflictException(DISABLED);
        }
        return available;
    }
}
