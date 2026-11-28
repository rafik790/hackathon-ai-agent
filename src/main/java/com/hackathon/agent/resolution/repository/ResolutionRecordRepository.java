package com.hackathon.agent.resolution.repository;

import com.hackathon.agent.resolution.entity.ResolutionRecordEntity;
import com.hackathon.agent.resolution.model.ResolutionTargetType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ResolutionRecordRepository extends JpaRepository<ResolutionRecordEntity, Long> {

    Optional<ResolutionRecordEntity> findByTargetKey(String targetKey);

    List<ResolutionRecordEntity> findByTargetKeyIn(Collection<String> targetKeys);

    /** TaskRun records that name this PipelineRun, also of TaskRuns that are no longer collected. */
    List<ResolutionRecordEntity> findByPipelineRunIdAndTargetType(Long pipelineRunId, ResolutionTargetType targetType);
}
