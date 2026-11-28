package com.hackathon.agent.resolution.api;

import com.hackathon.agent.enums.ExecutionStatus;
import com.hackathon.agent.investigation.model.AgentReport.Team;
import com.hackathon.agent.resolution.model.ResolutionStatus;
import com.hackathon.agent.resolution.model.ResolutionTargetType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Request and response bodies of the resolution API.
 */
public final class ResolutionDtos {

    private ResolutionDtos() {
    }

    /**
     * A change of a resolution. Fields left out (null) keep their value; an empty string clears a text field.
     * Enum values are strings so that invalid ones are reported as 400 with the allowed values.
     */
    @Schema(description = "Change of a resolution; fields left out keep their current value")
    public record ResolutionUpdateRequest(
            @Schema(example = "FIX_IN_PROGRESS", requiredMode = Schema.RequiredMode.REQUIRED,
                    description = "OPEN, INVESTIGATING, FIX_IN_PROGRESS, RESOLVED or REOPENED") String status,
            @Schema(description = "Version of the resolution the change is based on; null if it was not tracked yet",
                    example = "3") Long version,
            @Schema(example = "DEVOPS_PLATFORM", description = "DEVELOPER, DEVOPS_PLATFORM, CICD_ENGINEER or UNKNOWN") String assignedTeam,
            @Schema(example = "Priya") String assignedPerson,
            @Schema(example = "Granted the pipeline service account create on Deployments") String summary,
            @Schema(description = "Resolution notes kept on the record") String notes,
            @Schema(description = "Reason for this change, kept in the history") String comment,
            @Schema(description = "Who makes the change; the application has no user accounts", example = "priya")
            String changedBy) {
    }

    /**
     * For a TaskRun: its own record. For a PipelineRun: derived from its failed TaskRuns (read-only, never stored), with
     * empty nextStatuses and null version, person, notes and createdAt.
     */
    @Schema(description = "Resolution of a failed run. Separate from its execution status and from AI investigations. "
            + "For a PipelineRun it is derived from its failed TaskRuns and read-only.")
    public record ResolutionResponse(
            ResolutionTargetType targetType,
            Long taskRunId,
            Long pipelineRunId,
            @Schema(example = "demo-app-build-bb7hj-deploy") String targetName,
            @Schema(example = "ci") String namespace,
            @Schema(description = "Current Tekton status of the run; null if the run is no longer known") ExecutionStatus executionStatus,
            @Schema(description = "Whether a record exists (for a PipelineRun: for any of its failed TaskRuns); "
                    + "a failed TaskRun without one is OPEN") boolean tracked,
            ResolutionStatus status,
            @Schema(description = "Statuses the resolution may change to; empty for a PipelineRun") List<ResolutionStatus> nextStatuses,
            Team assignedTeam,
            String assignedPerson,
            String summary,
            String notes,
            String updatedBy,
            Instant createdAt,
            Instant updatedAt,
            Instant resolvedAt,
            @Schema(description = "Send back with the next update; null while not tracked") Long version,
            @Schema(description = "Only for PipelineRuns: its failed TaskRuns") PipelineTaskRunSummary taskRuns) {
    }

    @Schema(description = "Resolution of the failed TaskRuns of a PipelineRun")
    public record PipelineTaskRunSummary(
            int failedTaskRuns,
            int resolvedTaskRuns,
            int unresolvedTaskRuns,
            @Schema(description = "Failed TaskRuns per resolution status") Map<ResolutionStatus, Integer> byStatus,
            List<TaskRunResolutionItem> items) {
    }

    public record TaskRunResolutionItem(
            Long taskRunId,
            String taskRunName,
            String taskName,
            ExecutionStatus executionStatus,
            ResolutionStatus status,
            boolean tracked,
            Team assignedTeam,
            String summary,
            Instant updatedAt,
            Instant resolvedAt) {
    }

    @Schema(description = "One change of a TaskRun resolution, oldest first")
    public record ResolutionHistoryEntry(
            Long id,
            @Schema(description = "The TaskRun the change belongs to; null if it is no longer stored") Long taskRunId,
            String taskRunName,
            @Schema(description = "Null for the change that started tracking") ResolutionStatus previousStatus,
            ResolutionStatus newStatus,
            Team assignedTeam,
            String assignedPerson,
            String summary,
            @Schema(description = "Reason given for the change") String note,
            String changedBy,
            Instant changedAt) {
    }

    @Schema(description = "Resolution status of one run, for lists")
    public record ResolutionSummary(
            Long runId,
            boolean tracked,
            @Schema(description = "Null for a run without failed TaskRuns or a TaskRun that has not failed") ResolutionStatus status,
            Team assignedTeam,
            Instant updatedAt,
            @Schema(description = "Only for PipelineRuns: its failed TaskRuns") Integer failedTaskRuns,
            @Schema(description = "Only for PipelineRuns: how many of them are resolved") Integer resolvedTaskRuns) {
    }
}
