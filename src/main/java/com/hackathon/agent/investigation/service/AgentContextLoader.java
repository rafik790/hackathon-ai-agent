package com.hackathon.agent.investigation.service;

import com.hackathon.agent.dto.FailureAnalysisResponse;
import com.hackathon.agent.entity.PipelineRunEntity;
import com.hackathon.agent.entity.TaskRunEntity;
import com.hackathon.agent.enums.ExecutionStatus;
import com.hackathon.agent.evidence.entity.FailureEvidenceEntity;
import com.hackathon.agent.evidence.repository.FailureEvidenceRepository;
import com.hackathon.agent.exception.ResourceNotFoundException;
import com.hackathon.agent.investigation.entity.AgentInvestigationEntity;
import com.hackathon.agent.investigation.model.InvestigationModel.TargetType;
import com.hackathon.agent.investigation.tool.AgentToolRegistry;
import com.hackathon.agent.repository.PipelineRunRepository;
import com.hackathon.agent.repository.TaskRunRepository;
import com.hackathon.agent.service.FailureAnalysisQueryService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * What is known about the failure before the agent starts: the stored run, the collected evidence and the existing
 * AI analysis, if any. Read through existing repositories and services; nothing is changed.
 */
@Component
@ConditionalOnBooleanProperty(name = "agent.investigation.enabled")
public class AgentContextLoader {

    static final int MAX_TASKS = 30;

    private final TaskRunRepository taskRuns;
    private final PipelineRunRepository pipelineRuns;
    private final FailureEvidenceRepository evidence;
    private final FailureAnalysisQueryService analyses;
    private final AgentToolRegistry registry;

    public AgentContextLoader(TaskRunRepository taskRuns, PipelineRunRepository pipelineRuns, FailureEvidenceRepository evidence,
                              FailureAnalysisQueryService analyses, AgentToolRegistry registry) {
        this.taskRuns = taskRuns;
        this.pipelineRuns = pipelineRuns;
        this.evidence = evidence;
        this.analyses = analyses;
        this.registry = registry;
    }

    /** @return sanitized, bounded JSON */
    @Transactional(readOnly = true)
    public String load(AgentInvestigationEntity investigation) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("namespace", investigation.getNamespace());
        if (investigation.getTargetType() == TargetType.TASK_RUN) {
            Optional.ofNullable(investigation.getTaskRunId()).flatMap(taskRuns::findById).ifPresentOrElse(
                    taskRun -> context.put("failedTaskRun", taskRun(taskRun, true)),
                    () -> context.put("failedTaskRun", Map.of("name", investigation.getTargetName(), "note", "no longer stored")));
        } else {
            Optional.ofNullable(investigation.getPipelineRunId()).flatMap(pipelineRuns::findById).ifPresentOrElse(
                    run -> context.put("failedPipelineRun", pipelineRun(run)),
                    () -> context.put("failedPipelineRun", Map.of("name", investigation.getTargetName(), "note", "no longer stored")));
        }
        return registry.format(context);
    }

    private Map<String, Object> pipelineRun(PipelineRunEntity run) {
        Map<String, Object> result = ordered("name", run.getPipelineRunName(), "pipeline", run.getPipelineName(),
                "status", run.getStatus().name(), "startTime", text(run.getStartTime()), "completionTime", text(run.getCompletionTime()),
                "failureReason", run.getFailureReason(), "failureMessage", run.getFailureMessage());
        List<TaskRunEntity> tasks = taskRuns.findByPipelineRun(run.getPipelineRunUid(), run.getNamespace(), run.getPipelineRunName());
        result.put("tasks", tasks.stream().limit(MAX_TASKS).map(task -> taskRun(task, false)).toList());
        // The existing analysis of the first failed task is the best starting point.
        tasks.stream().filter(task -> task.getStatus() == ExecutionStatus.FAILED).findFirst()
                .flatMap(task -> analysis(task.getId()))
                .ifPresent(analysis -> result.put("existingAnalysisOfFirstFailedTask", analysis));
        return result;
    }

    private Map<String, Object> taskRun(TaskRunEntity task, boolean details) {
        Map<String, Object> result = ordered("name", task.getTaskRunName(), "task", task.getTaskName(),
                "status", task.getStatus().name(), "failureReason", task.getFailureReason(), "failureMessage", task.getFailureMessage());
        if (details) {
            result.putAll(ordered("pipelineRun", task.getPipelineRunName(), "pipeline", task.getPipelineName(),
                    "startTime", text(task.getStartTime()), "completionTime", text(task.getCompletionTime())));
            List<FailureEvidenceEntity> attempts = evidence.findByTaskRunIdOrderByAttempt(task.getId());
            if (!attempts.isEmpty()) {
                FailureEvidenceEntity last = attempts.getLast();
                result.put("evidence", ordered("status", last.getStatus().name(), "podName", last.getPodName(),
                        "attempts", attempts.size()));
            }
            analysis(task.getId()).ifPresent(analysis -> result.put("existingAnalysis", analysis));
        }
        return result;
    }

    /** The existing failure analysis, which may be wrong and is therefore only a hint. */
    private Optional<Map<String, Object>> analysis(Long taskRunId) {
        try {
            FailureAnalysisResponse analysis = analyses.latestOfTaskRun(taskRunId);
            return Optional.of(ordered("status", analysis.status().name(),
                    "category", analysis.failureCategory() == null ? null : analysis.failureCategory().name(),
                    "confidence", analysis.confidence(), "summary", analysis.summary(), "rootCause", analysis.rootCause()));
        } catch (ResourceNotFoundException e) {
            return Optional.empty();
        }
    }

    private static String text(Object value) {
        return value == null ? null : value.toString();
    }

    private static Map<String, Object> ordered(Object... keysAndValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keysAndValues.length; i += 2) {
            if (keysAndValues[i + 1] != null) {
                map.put((String) keysAndValues[i], keysAndValues[i + 1]);
            }
        }
        return map;
    }
}
