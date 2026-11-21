package com.hackathon.agent.service;

import com.hackathon.agent.dto.PageResponse;
import com.hackathon.agent.dto.TaskRunResponse;
import com.hackathon.agent.entity.TaskRunEntity;
import com.hackathon.agent.exception.ResourceNotFoundException;
import com.hackathon.agent.mapper.ExecutionMapper;
import com.hackathon.agent.repository.ExecutionFilter;
import com.hackathon.agent.repository.ExecutionSpecifications;
import com.hackathon.agent.repository.TaskRunRepository;
import java.util.Set;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class TaskRunQueryService {

    static final Set<String> SORTABLE = Set.of("startTime", "completionTime", "durationMs", "status",
            "pipelineName", "pipelineRunName", "taskName", "taskRunName", "namespace", "createdAt", "updatedAt", "id");

    private final TaskRunRepository repository;

    public TaskRunQueryService(TaskRunRepository repository) {
        this.repository = repository;
    }

    public PageResponse<TaskRunResponse> find(ExecutionFilter filter, String taskName, String pipelineRunName,
                                              Pageable pageable) {
        PipelineRunQueryService.validateWindow(filter);
        SortValidator.validate(pageable, SORTABLE);
        Specification<TaskRunEntity> spec = ExecutionSpecifications.<TaskRunEntity>matching(filter)
                .and(ExecutionSpecifications.attributeEquals("taskName", taskName))
                .and(ExecutionSpecifications.attributeEquals("pipelineRunName", pipelineRunName));
        return PageResponse.from(repository.findAll(spec, pageable), ExecutionMapper::toResponse);
    }

    public TaskRunResponse get(long id) {
        return repository.findById(id)
                .map(ExecutionMapper::toResponse)
                .orElseThrow(() -> new ResourceNotFoundException("TaskRun", id));
    }
}
