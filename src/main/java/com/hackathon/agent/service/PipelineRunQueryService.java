package com.hackathon.agent.service;

import com.hackathon.agent.dto.PageResponse;
import com.hackathon.agent.dto.PipelineRunResponse;
import com.hackathon.agent.dto.TaskRunResponse;
import com.hackathon.agent.entity.PipelineRunEntity;
import com.hackathon.agent.exception.InvalidRequestException;
import com.hackathon.agent.exception.ResourceNotFoundException;
import com.hackathon.agent.mapper.ExecutionMapper;
import com.hackathon.agent.repository.ExecutionFilter;
import com.hackathon.agent.repository.ExecutionSpecifications;
import com.hackathon.agent.repository.PipelineRunRepository;
import com.hackathon.agent.repository.TaskRunRepository;
import java.util.List;
import java.util.Set;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class PipelineRunQueryService {

    static final Set<String> SORTABLE = Set.of("startTime", "completionTime", "durationMs", "status",
            "pipelineName", "pipelineRunName", "namespace", "createdAt", "updatedAt", "id");

    private final PipelineRunRepository pipelineRunRepository;
    private final TaskRunRepository taskRunRepository;

    public PipelineRunQueryService(PipelineRunRepository pipelineRunRepository, TaskRunRepository taskRunRepository) {
        this.pipelineRunRepository = pipelineRunRepository;
        this.taskRunRepository = taskRunRepository;
    }

    public PageResponse<PipelineRunResponse> find(ExecutionFilter filter, Pageable pageable) {
        validateWindow(filter);
        SortValidator.validate(pageable, SORTABLE);
        return PageResponse.from(
                pipelineRunRepository.findAll(ExecutionSpecifications.<PipelineRunEntity>matching(filter), pageable),
                ExecutionMapper::toResponse);
    }

    public PipelineRunResponse get(long id) {
        return ExecutionMapper.toResponse(load(id));
    }

    public List<TaskRunResponse> tasksOf(long id) {
        PipelineRunEntity pipelineRun = load(id);
        return taskRunRepository.findByPipelineRun(pipelineRun.getPipelineRunUid(), pipelineRun.getNamespace(),
                        pipelineRun.getPipelineRunName())
                .stream()
                .map(ExecutionMapper::toResponse)
                .toList();
    }

    private PipelineRunEntity load(long id) {
        return pipelineRunRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("PipelineRun", id));
    }

    static void validateWindow(ExecutionFilter filter) {
        if (filter.from() != null && filter.to() != null && !filter.from().isBefore(filter.to())) {
            throw new InvalidRequestException("'from' must be before 'to'");
        }
    }
}
