package com.hackathon.agent.resolution.api;

import com.hackathon.agent.dto.ErrorResponse;
import com.hackathon.agent.resolution.api.ResolutionDtos.ResolutionHistoryEntry;
import com.hackathon.agent.resolution.api.ResolutionDtos.ResolutionResponse;
import com.hackathon.agent.resolution.api.ResolutionDtos.ResolutionSummary;
import com.hackathon.agent.resolution.api.ResolutionDtos.ResolutionUpdateRequest;
import com.hackathon.agent.resolution.service.ResolutionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Resolution of failed runs, under the existing /tasks and /pipelines resources. TaskRun resolutions are changed here;
 * PipelineRun resolutions are derived from them and have no update. Only the database is involved: no Kubernetes
 * resource is read or changed.
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Resolution", description = "Tracking of the work on failed TaskRuns and PipelineRuns")
public class ResolutionController {

    private final ResolutionService service;

    public ResolutionController(ResolutionService service) {
        this.service = service;
    }

    @GetMapping("/tasks/{id}/resolution")
    @Operation(summary = "Resolution of a TaskRun", description = "A failed TaskRun without a record is OPEN with tracked=false.")
    @ApiResponse(responseCode = "404", description = "Unknown TaskRun", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    public ResolutionResponse taskRun(@PathVariable long id) {
        return service.taskRun(id);
    }

    @PutMapping("/tasks/{id}/resolution")
    @Operation(summary = "Change the resolution of a TaskRun",
            description = "Validates the transition and the version, then records the change in the history.")
    @ApiResponse(responseCode = "400", description = "Invalid status, team or text", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "404", description = "Unknown TaskRun", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "409", description = "Transition not allowed, stale version, or the TaskRun has not failed",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    public ResolutionResponse updateTaskRun(@PathVariable long id, @RequestBody ResolutionUpdateRequest request) {
        return service.updateTaskRun(id, request);
    }

    @GetMapping("/tasks/{id}/resolution/history")
    @Operation(summary = "Resolution history of a TaskRun", description = "Oldest first; empty while not tracked.")
    public List<ResolutionHistoryEntry> taskRunHistory(@PathVariable long id) {
        return service.taskRunHistory(id);
    }

    @GetMapping("/tasks/resolutions")
    @Operation(summary = "Resolution status of several TaskRuns", description = "Unknown ids are left out.")
    public List<ResolutionSummary> taskRunSummaries(@Parameter(description = "1 to 100 TaskRun ids") @RequestParam List<Long> ids) {
        return service.taskRunSummaries(ids);
    }

    @GetMapping("/pipelines/{id}/resolution")
    @Operation(summary = "Resolution of a PipelineRun (derived)",
            description = "Aggregated from the resolutions of its failed TaskRuns and read-only: change a TaskRun to change it. "
                    + "No failed TaskRuns: status null. All resolved: RESOLVED. Otherwise the first of REOPENED, FIX_IN_PROGRESS, "
                    + "INVESTIGATING, OPEN found among them; a failed TaskRun without a record counts as OPEN.")
    @ApiResponse(responseCode = "404", description = "Unknown PipelineRun", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    public ResolutionResponse pipelineRun(@PathVariable long id) {
        return service.pipelineRun(id);
    }

    @GetMapping("/pipelines/{id}/resolution/history")
    @Operation(summary = "Resolution history of a PipelineRun", description = "Changes of its failed TaskRuns, oldest first.")
    public List<ResolutionHistoryEntry> pipelineRunHistory(@PathVariable long id) {
        return service.pipelineRunHistory(id);
    }

    @GetMapping("/pipelines/resolutions")
    @Operation(summary = "Resolution status of several PipelineRuns", description = "Unknown ids are left out.")
    public List<ResolutionSummary> pipelineRunSummaries(@Parameter(description = "1 to 100 PipelineRun ids") @RequestParam List<Long> ids) {
        return service.pipelineRunSummaries(ids);
    }
}
