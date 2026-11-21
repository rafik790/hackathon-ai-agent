package com.hackathon.agent.repository;

import com.hackathon.agent.entity.PipelineRunEntity;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface PipelineRunRepository
        extends JpaRepository<PipelineRunEntity, Long>, JpaSpecificationExecutor<PipelineRunEntity> {

    Optional<PipelineRunEntity> findByPipelineRunUid(String pipelineRunUid);

    List<PipelineRunEntity> findByPipelineRunUidIn(Collection<String> pipelineRunUids);
}
