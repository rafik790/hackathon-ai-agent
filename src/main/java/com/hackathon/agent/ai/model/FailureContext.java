package com.hackathon.agent.ai.model;

import com.hackathon.agent.enums.ExecutionStatus;
import com.hackathon.agent.evidence.model.ContainerRunState;
import com.hackathon.agent.evidence.model.ContainerType;
import com.hackathon.agent.evidence.model.EvidenceStatus;
import com.hackathon.agent.evidence.model.ExitCodeSource;
import com.hackathon.agent.evidence.model.LogStatus;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Everything known about a failure, bounded in size and free of recognizable credentials. Independent of any LLM.
 *
 * @param pipelineRun   null for TaskRuns without a known PipelineRun
 * @param otherTaskRuns other TaskRuns of the same PipelineRun, in order of execution
 * @param limitations   what is missing or incomplete
 */
public record FailureContext(
        PipelineRun pipelineRun,
        TaskRun taskRun,
        EvidenceStatus evidenceStatus,
        Pod pod,
        List<Container> containers,
        List<ContainerLog> logs,
        List<OtherTaskRun> otherTaskRuns,
        List<String> limitations) {

    public record PipelineRun(String name, String uid, String pipelineName, String namespace, ExecutionStatus status,
                              Instant startTime, Instant completionTime, String failureReason, String failureMessage) {
    }

    /**
     * @param attempt       zero-based attempt this evidence belongs to
     * @param totalAttempts attempts with evidence
     */
    public record TaskRun(String name, String uid, String taskName, String namespace, ExecutionStatus status,
                          Instant startTime, Instant completionTime, String failureReason, String failureMessage,
                          int attempt, int totalAttempts) {
    }

    public record Pod(String name, String uid, String phase, String reason, String message) {
    }

    public record Container(String name, ContainerType type, String stepName, Integer ordinal, ContainerRunState state,
                            Integer exitCode, ExitCodeSource exitCodeSource, Integer signal, String reason,
                            String tektonTerminationReason, String message, Integer restartCount,
                            boolean primaryFailure) {
    }

    /**
     * @param truncated whether earlier lines are missing
     * @param content   null when the log is not available
     */
    public record ContainerLog(String containerName, boolean previousInstance, LogStatus availability,
                               boolean truncated, String content) {
    }

    public record OtherTaskRun(String name, String taskName, ExecutionStatus status, Instant startTime,
                               Instant completionTime, String failureReason, String failureMessage) {
    }

    public Set<String> containerNames() {
        return containers.stream().map(Container::name).collect(Collectors.toUnmodifiableSet());
    }
}
