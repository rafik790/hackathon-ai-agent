package com.hackathon.agent.ai.context;

import com.hackathon.agent.ai.model.FailureContext;
import com.hackathon.agent.config.AiProperties;
import com.hackathon.agent.entity.PipelineRunEntity;
import com.hackathon.agent.entity.TaskRunEntity;
import com.hackathon.agent.evidence.entity.ContainerEvidenceEntity;
import com.hackathon.agent.evidence.entity.ContainerLogEntity;
import com.hackathon.agent.evidence.entity.FailureEvidenceEntity;
import com.hackathon.agent.evidence.model.EvidenceStatus;
import com.hackathon.agent.evidence.model.LogStatus;
import com.hackathon.agent.evidence.repository.ContainerEvidenceRepository;
import com.hackathon.agent.evidence.repository.ContainerLogRepository;
import com.hackathon.agent.evidence.repository.FailureEvidenceRepository;
import com.hackathon.agent.evidence.sanitizer.LogSanitizer;
import com.hackathon.agent.exception.ResourceNotFoundException;
import com.hackathon.agent.repository.PipelineRunRepository;
import com.hackathon.agent.repository.TaskRunRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Assembles what is known about a failure from the database. The cluster is never contacted.
 * <p>
 * All free text passes the sanitizer again: logs were redacted when they were collected, but messages stored by
 * the monitoring collector were not.
 */
@Component
public class FailureContextBuilder {

    static final int MAX_CONTAINERS = 20;
    static final int MAX_OTHER_TASK_RUNS = 30;
    static final int MAX_MESSAGE_CHARS = 2000;
    static final int MAX_OTHER_MESSAGE_CHARS = 300;
    /** A single log may use at most this share of the log budget, so that one log cannot hide the others. */
    private static final int MAX_LOG_SHARE = 2;

    private final FailureEvidenceRepository evidenceRepository;
    private final TaskRunRepository taskRunRepository;
    private final PipelineRunRepository pipelineRunRepository;
    private final ContainerEvidenceRepository containerRepository;
    private final ContainerLogRepository logRepository;
    private final LogSanitizer sanitizer;
    private final AiProperties properties;
    private final Clock clock;

    public FailureContextBuilder(FailureEvidenceRepository evidenceRepository, TaskRunRepository taskRunRepository,
                                 PipelineRunRepository pipelineRunRepository,
                                 ContainerEvidenceRepository containerRepository,
                                 ContainerLogRepository logRepository, LogSanitizer sanitizer,
                                 AiProperties properties, Clock clock) {
        this.evidenceRepository = evidenceRepository;
        this.taskRunRepository = taskRunRepository;
        this.pipelineRunRepository = pipelineRunRepository;
        this.containerRepository = containerRepository;
        this.logRepository = logRepository;
        this.sanitizer = sanitizer;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public FailureContext build(Long evidenceId) {
        FailureEvidenceEntity evidence = evidenceRepository.findById(evidenceId)
                .orElseThrow(() -> new ResourceNotFoundException("FailureEvidence", evidenceId));
        TaskRunEntity taskRun = taskRunRepository.findById(evidence.getTaskRunId())
                .orElseThrow(() -> new ResourceNotFoundException("TaskRun", evidence.getTaskRunId()));
        int totalAttempts = evidenceRepository.findByTaskRunIdOrderByAttempt(taskRun.getId()).size();
        List<String> limitations = new ArrayList<>();

        List<ContainerEvidenceEntity> containers = containerRepository.findByEvidenceId(evidenceId).stream()
                .sorted(Comparator.comparing((ContainerEvidenceEntity c) -> !c.isPrimaryFailure())
                        .thenComparing(ContainerEvidenceEntity::getContainerType)
                        .thenComparing(ContainerEvidenceEntity::getOrdinal, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(ContainerEvidenceEntity::getContainerName))
                .toList();
        if (containers.size() > MAX_CONTAINERS) {
            limitations.add("Only %d of %d containers are included.".formatted(MAX_CONTAINERS, containers.size()));
            containers = containers.subList(0, MAX_CONTAINERS);
        }
        List<FailureContext.ContainerLog> logs = logs(containers, limitations);
        describeEvidence(evidence, containers, logs, limitations);

        return new FailureContext(
                pipelineRun(taskRun),
                new FailureContext.TaskRun(taskRun.getTaskRunName(), taskRun.getTaskRunUid(), taskRun.getTaskName(),
                        taskRun.getNamespace(), taskRun.getStatus(), taskRun.getStartTime(),
                        taskRun.getCompletionTime(), taskRun.getFailureReason(),
                        message(taskRun.getFailureMessage(), MAX_MESSAGE_CHARS), evidence.getAttempt(), totalAttempts),
                evidence.getStatus(),
                evidence.getPodName() == null ? null : new FailureContext.Pod(evidence.getPodName(),
                        evidence.getPodUid(), evidence.getPodPhase(), evidence.getPodReason(),
                        message(evidence.getPodMessage(), MAX_MESSAGE_CHARS)),
                containers.stream().map(this::container).toList(),
                List.copyOf(logs),
                otherTaskRuns(taskRun, limitations),
                List.copyOf(limitations));
    }

    private FailureContext.PipelineRun pipelineRun(TaskRunEntity taskRun) {
        if (taskRun.getPipelineRunUid() == null) {
            return null;
        }
        PipelineRunEntity run = pipelineRunRepository.findByPipelineRunUid(taskRun.getPipelineRunUid()).orElse(null);
        if (run == null) {
            return null;
        }
        return new FailureContext.PipelineRun(run.getPipelineRunName(), run.getPipelineRunUid(), run.getPipelineName(),
                run.getNamespace(), run.getStatus(), run.getStartTime(), run.getCompletionTime(),
                run.getFailureReason(), message(run.getFailureMessage(), MAX_MESSAGE_CHARS));
    }

    /** The other tasks show whether this failure was the first one or a consequence. */
    private List<FailureContext.OtherTaskRun> otherTaskRuns(TaskRunEntity taskRun, List<String> limitations) {
        if (taskRun.getPipelineRunUid() == null) {
            return List.of();
        }
        List<TaskRunEntity> others = taskRunRepository.findByPipelineRun(taskRun.getPipelineRunUid(),
                        taskRun.getNamespace(), taskRun.getPipelineRunName()).stream()
                .filter(other -> !other.getId().equals(taskRun.getId()))
                .toList();
        if (others.size() > MAX_OTHER_TASK_RUNS) {
            limitations.add("Only %d of %d other tasks of the pipeline are included.".formatted(MAX_OTHER_TASK_RUNS, others.size()));
        }
        return others.stream().limit(MAX_OTHER_TASK_RUNS)
                .map(other -> new FailureContext.OtherTaskRun(other.getTaskRunName(), other.getTaskName(),
                        other.getStatus(), other.getStartTime(), other.getCompletionTime(), other.getFailureReason(),
                        message(other.getFailureMessage(), MAX_OTHER_MESSAGE_CHARS)))
                .toList();
    }

    private FailureContext.Container container(ContainerEvidenceEntity c) {
        return new FailureContext.Container(c.getContainerName(), c.getContainerType(), c.getStepName(),
                c.getOrdinal(), c.getState(), c.getExitCode(), c.getExitCodeSource(), c.getSignal(), c.getReason(),
                c.getTektonTerminationReason(), message(c.getMessage(), MAX_MESSAGE_CHARS), c.getRestartCount(),
                c.isPrimaryFailure());
    }

    /** Containers are ordered by relevance, so the primary failure uses the log budget first. */
    private List<FailureContext.ContainerLog> logs(List<ContainerEvidenceEntity> containers, List<String> limitations) {
        int budget = properties.analysis().maxLogChars();
        int maxPerLog = Math.max(1, budget / MAX_LOG_SHARE);
        int remaining = budget;
        boolean shortened = false;
        Instant now = clock.instant();
        List<FailureContext.ContainerLog> logs = new ArrayList<>();
        for (ContainerEvidenceEntity container : containers) {
            List<ContainerLogEntity> entities = logRepository.findByContainerEvidenceId(container.getId()).stream()
                    .sorted(Comparator.comparing(ContainerLogEntity::isPrevious))
                    .toList();
            for (ContainerLogEntity entity : entities) {
                boolean expired = entity.getExpiresAt() != null && entity.getExpiresAt().isBefore(now);
                LogStatus availability = expired ? LogStatus.EXPIRED : entity.getStatus();
                if (availability != LogStatus.COLLECTED || entity.getContent() == null) {
                    logs.add(new FailureContext.ContainerLog(container.getContainerName(), entity.isPrevious(),
                            availability, false, null));
                    continue;
                }
                String text = sanitizer.sanitizeMessage(entity.getContent(), Integer.MAX_VALUE);
                String tail = text == null ? "" : tail(text, Math.min(maxPerLog, remaining));
                boolean cut = text != null && tail.length() < text.length();
                shortened |= cut;
                remaining -= tail.length();
                logs.add(tail.isEmpty()
                        ? new FailureContext.ContainerLog(container.getContainerName(), entity.isPrevious(),
                        LogStatus.SKIPPED_LIMIT, false, null)
                        : new FailureContext.ContainerLog(container.getContainerName(), entity.isPrevious(),
                        LogStatus.COLLECTED, cut || entity.isTruncated(), tail));
            }
        }
        if (shortened) {
            limitations.add("Logs were shortened to their last part to limit the size of this analysis.");
        }
        return logs;
    }

    private void describeEvidence(FailureEvidenceEntity evidence, List<ContainerEvidenceEntity> containers,
                                  List<FailureContext.ContainerLog> logs, List<String> limitations) {
        EvidenceStatus status = evidence.getStatus();
        switch (status) {
            case COLLECTED -> { }
            case NO_POD -> limitations.add("No Pod was created, so there are no containers and no logs.");
            case POD_DELETED -> limitations.add("The Pod was already deleted, so there are no logs. Tekton deletes Pods "
                    + "of timed out and cancelled TaskRuns.");
            default -> limitations.add("The evidence is incomplete (" + status + ").");
        }
        if (containers.isEmpty()) {
            limitations.add("No container information is available.");
        }
        if (logs.stream().noneMatch(log -> log.availability() == LogStatus.COLLECTED)) {
            limitations.add("No container logs are available.");
        }
    }

    private String message(String text, int maxChars) {
        return sanitizer.sanitizeMessage(text, maxChars);
    }

    /** Last part of the text, starting at a line where possible. */
    private static String tail(String text, int maxChars) {
        if (text.length() <= maxChars) {
            return text;
        }
        if (maxChars <= 0) {
            return "";
        }
        int start = text.length() - maxChars;
        int lineStart = text.indexOf('\n', start - 1) + 1;
        if (lineStart > 0 && lineStart < text.length() && lineStart - start <= maxChars / 10) {
            return text.substring(lineStart);
        }
        return text.substring(Character.isLowSurrogate(text.charAt(start)) ? start + 1 : start);
    }
}
