package com.hackathon.agent.investigation.service;

import com.hackathon.agent.ai.llm.LlmProvider;
import com.hackathon.agent.ai.model.QuotaStatus;
import com.hackathon.agent.ai.service.AiQuotaService;
import com.hackathon.agent.dto.PageResponse;
import com.hackathon.agent.entity.PipelineRunEntity;
import com.hackathon.agent.entity.TaskRunEntity;
import com.hackathon.agent.enums.ExecutionStatus;
import com.hackathon.agent.exception.ConflictException;
import com.hackathon.agent.exception.InvalidRequestException;
import com.hackathon.agent.exception.QuotaExceededException;
import com.hackathon.agent.exception.ResourceNotFoundException;
import com.hackathon.agent.investigation.api.AgentInvestigationDtos.InvestigationResponse;
import com.hackathon.agent.investigation.api.AgentInvestigationDtos.InvestigationStepResponse;
import com.hackathon.agent.investigation.api.AgentInvestigationDtos.StartInvestigationRequest;
import com.hackathon.agent.investigation.entity.AgentInvestigationEntity;
import com.hackathon.agent.investigation.entity.AgentInvestigationStepEntity;
import com.hackathon.agent.investigation.model.InvestigationModel.InvestigationStatus;
import com.hackathon.agent.investigation.model.InvestigationModel.TargetType;
import com.hackathon.agent.investigation.repository.AgentInvestigationRepository;
import com.hackathon.agent.investigation.repository.AgentInvestigationStepRepository;
import com.hackathon.agent.investigation.repository.AgentPipelineRunLookup;
import com.hackathon.agent.investigation.repository.AgentTaskRunLookup;
import com.hackathon.agent.investigation.tool.AgentToolRegistry;
import jakarta.persistence.criteria.Predicate;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Starts investigations and reads them. Starting only queues an investigation; the worker runs it.
 */
@Service
@ConditionalOnBooleanProperty(name = "agent.investigation.enabled")
public class AgentInvestigationService {

    static final Set<String> SORTABLE = Set.of("id", "status", "createdAt", "startedAt", "completedAt", "namespace", "targetName");

    private final AgentInvestigationRepository investigations;
    private final AgentInvestigationStepRepository steps;
    private final AgentTaskRunLookup taskRuns;
    private final AgentPipelineRunLookup pipelineRuns;
    private final AgentToolRegistry registry;
    private final ObjectProvider<LlmProvider> providers;
    private final AiQuotaService quota;
    private final Clock clock;

    public AgentInvestigationService(AgentInvestigationRepository investigations, AgentInvestigationStepRepository steps,
                                     AgentTaskRunLookup taskRuns, AgentPipelineRunLookup pipelineRuns, AgentToolRegistry registry,
                                     ObjectProvider<LlmProvider> providers, AiQuotaService quota, Clock clock) {
        this.investigations = investigations;
        this.steps = steps;
        this.taskRuns = taskRuns;
        this.pipelineRuns = pipelineRuns;
        this.registry = registry;
        this.providers = providers;
        this.quota = quota;
        this.clock = clock;
    }

    /**
     * Queues an investigation of a failed run, or returns the one already queued or running for it.
     */
    @Transactional
    public InvestigationResponse start(StartInvestigationRequest request) {
        boolean byTaskRun = request.taskRunName() != null && !request.taskRunName().isBlank();
        boolean byPipelineRun = request.pipelineRunName() != null && !request.pipelineRunName().isBlank();
        if (byTaskRun == byPipelineRun) {
            throw new InvalidRequestException("Exactly one of taskRunName and pipelineRunName is required");
        }
        if (request.namespace() == null || request.namespace().isBlank()) {
            throw new InvalidRequestException("namespace is required");
        }
        String name = (byTaskRun ? request.taskRunName() : request.pipelineRunName()).trim();
        String namespace = request.namespace().trim();
        registry.validate(name, namespace).ifPresent(reason -> {
            throw new InvalidRequestException(reason.replace("resourceName", byTaskRun ? "taskRunName" : "pipelineRunName"));
        });
        LlmProvider provider = providers.getIfAvailable();
        if (provider == null) {
            throw new ConflictException("Agent investigation needs AI analysis; it is disabled (AI_ENABLED=false)");
        }

        AgentInvestigationEntity investigation = new AgentInvestigationEntity();
        investigation.setNamespace(namespace);
        investigation.setTargetName(name);
        if (byTaskRun) {
            TaskRunEntity taskRun = taskRuns.findFirstByNamespaceAndTaskRunNameOrderByIdDesc(namespace, name)
                    .orElseThrow(() -> new ResourceNotFoundException("TaskRun", namespace + "/" + name));
            requireFailed(taskRun.getStatus(), "TaskRun", name);
            investigation.setTargetType(TargetType.TASK_RUN);
            investigation.setTaskRunId(taskRun.getId());
        } else {
            PipelineRunEntity run = pipelineRuns.findFirstByNamespaceAndPipelineRunNameOrderByIdDesc(namespace, name)
                    .orElseThrow(() -> new ResourceNotFoundException("PipelineRun", namespace + "/" + name));
            requireFailed(run.getStatus(), "PipelineRun", name);
            investigation.setTargetType(TargetType.PIPELINE_RUN);
            investigation.setPipelineRunId(run.getId());
        }

        Optional<AgentInvestigationEntity> active = investigations
                .findFirstByNamespaceAndTargetTypeAndTargetNameAndStatusInOrderByIdDesc(namespace, investigation.getTargetType(),
                        name, List.of(InvestigationStatus.PENDING, InvestigationStatus.RUNNING));
        if (active.isPresent()) {
            return toResponse(active.get());
        }
        QuotaStatus status = quota.status(provider);
        if (status.isExhausted()) {
            throw new QuotaExceededException("The daily AI quota of %d invocations is exhausted; it becomes available again at %s"
                    .formatted(status.limit(), status.availableAt()), status.availableAt());
        }
        Instant now = clock.instant();
        investigation.setStatus(InvestigationStatus.PENDING);
        investigation.setCreatedAt(now);
        investigation.setUpdatedAt(now);
        return toResponse(investigations.save(investigation));
    }

    @Transactional(readOnly = true)
    public InvestigationResponse get(long id) {
        return toResponse(find(id));
    }

    @Transactional(readOnly = true)
    public List<InvestigationStepResponse> steps(long id) {
        find(id);
        return steps.findByInvestigationIdOrderByStepNumber(id).stream().map(AgentInvestigationService::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public PageResponse<InvestigationResponse> list(String taskRunName, String pipelineRunName, String namespace,
                                                    InvestigationStatus status, Pageable pageable) {
        for (Sort.Order order : pageable.getSort()) {
            if (!SORTABLE.contains(order.getProperty())) {
                throw new InvalidRequestException("Cannot sort by '" + order.getProperty() + "'. Sortable: " + String.join(", ",
                        SORTABLE.stream().sorted().toList()));
            }
        }
        Specification<AgentInvestigationEntity> filter = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (taskRunName != null && !taskRunName.isBlank()) {
                predicates.add(cb.equal(root.get("targetType"), TargetType.TASK_RUN));
                predicates.add(cb.equal(root.get("targetName"), taskRunName.trim()));
            }
            if (pipelineRunName != null && !pipelineRunName.isBlank()) {
                predicates.add(cb.equal(root.get("targetType"), TargetType.PIPELINE_RUN));
                predicates.add(cb.equal(root.get("targetName"), pipelineRunName.trim()));
            }
            if (namespace != null && !namespace.isBlank()) {
                predicates.add(cb.equal(root.get("namespace"), namespace.trim()));
            }
            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
        return PageResponse.from(investigations.findAll(filter, pageable), AgentInvestigationService::toResponse);
    }

    private AgentInvestigationEntity find(long id) {
        return investigations.findById(id).orElseThrow(() -> new ResourceNotFoundException("AgentInvestigation", id));
    }

    private static void requireFailed(ExecutionStatus status, String kind, String name) {
        if (status != ExecutionStatus.FAILED) {
            throw new ConflictException("%s %s has status %s; only failed runs can be investigated".formatted(kind, name, status));
        }
    }

    static InvestigationResponse toResponse(AgentInvestigationEntity e) {
        return new InvestigationResponse(e.getId(), e.getTargetType(), e.getTargetName(), e.getNamespace(), e.getTaskRunId(),
                e.getPipelineRunId(), e.getStatus(), e.getStepCount(), e.getToolCallCount(), e.getSummary(), e.getRootCause(),
                e.getFailureCategory(), e.getConfidence(), e.getReport(), e.getErrorMessage(), e.getProvider(), e.getModel(),
                e.getInputTokens(), e.getOutputTokens(), e.getCreatedAt(), e.getStartedAt(), e.getCompletedAt());
    }

    static InvestigationStepResponse toResponse(AgentInvestigationStepEntity s) {
        return new InvestigationStepResponse(s.getStepNumber(), s.getAction(), s.getToolName(), s.getArguments(), s.getReasoning(),
                s.getStatus(), s.getResultSummary(), s.getErrorMessage(), s.getDurationMs(), s.getCreatedAt());
    }
}
