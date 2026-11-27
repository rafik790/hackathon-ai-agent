package com.hackathon.agent.investigation.api;

import com.hackathon.agent.investigation.model.AgentReport;
import com.hackathon.agent.investigation.model.InvestigationModel.InvestigationStatus;
import com.hackathon.agent.investigation.model.InvestigationModel.StepAction;
import com.hackathon.agent.investigation.model.InvestigationModel.StepStatus;
import com.hackathon.agent.investigation.model.InvestigationModel.TargetType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

/**
 * Request and response bodies of the agent investigation API.
 */
public final class AgentInvestigationDtos {

    private AgentInvestigationDtos() {
    }

    /** Validated by the service, which reports errors in the format of the existing API. */
    @Schema(description = "The failed run to investigate: either a TaskRun or a PipelineRun, by name")
    public record StartInvestigationRequest(
            @Schema(example = "demo-app-build-bb7hj-build-and-push") String taskRunName,
            @Schema(example = "demo-app-build-bb7hj") String pipelineRunName,
            @Schema(example = "hackathon", requiredMode = Schema.RequiredMode.REQUIRED) String namespace) {
    }

    @Schema(description = "An agent investigation. Result fields are null until it is COMPLETED.")
    public record InvestigationResponse(
            @Schema(example = "1") Long id,
            @Schema(example = "TASK_RUN") TargetType targetType,
            @Schema(example = "demo-app-build-restricted-cwcrf-deploy") String targetName,
            @Schema(example = "hackathon") String namespace,
            Long taskRunId,
            Long pipelineRunId,
            @Schema(example = "COMPLETED") InvestigationStatus status,
            @Schema(description = "Questions to the model so far") int stepCount,
            @Schema(description = "Executed tools so far") int toolCallCount,
            String summary,
            String rootCause,
            @Schema(example = "PERMISSION_DENIED") String failureCategory,
            @Schema(example = "0.9") BigDecimal confidence,
            AgentReport report,
            @Schema(description = "Why the investigation failed") String errorMessage,
            String provider,
            String model,
            int inputTokens,
            int outputTokens,
            Instant createdAt,
            Instant startedAt,
            Instant completedAt) {
    }

    @Schema(description = "One step of an investigation: the tool the model chose, or its final report")
    public record InvestigationStepResponse(
            int stepNumber,
            StepAction action,
            @Schema(example = "get_taskrun_logs") String toolName,
            @Schema(description = "Sanitized arguments as requested by the model") Map<String, String> arguments,
            @Schema(description = "Why the model chose this step") String reasoning,
            StepStatus status,
            @Schema(description = "Sanitized, shortened tool result") String resultSummary,
            String errorMessage,
            Long durationMs,
            Instant createdAt) {
    }
}
