package com.hackathon.agent.investigation.repository;

import com.hackathon.agent.entity.PipelineRunEntity;
import com.hackathon.agent.enums.ExecutionStatus;
import java.util.Optional;
import org.springframework.data.repository.Repository;

/**
 * Read-only lookups of PipelineRuns stored by the collector. A name may be reused after a run was deleted, so the
 * newest row wins.
 */
public interface AgentPipelineRunLookup extends Repository<PipelineRunEntity, Long> {

    Optional<PipelineRunEntity> findFirstByNamespaceAndPipelineRunNameOrderByIdDesc(String namespace, String pipelineRunName);

    Optional<PipelineRunEntity> findFirstByNamespaceAndPipelineNameAndStatusOrderByCompletionTimeDescIdDesc(
            String namespace, String pipelineName, ExecutionStatus status);
}
