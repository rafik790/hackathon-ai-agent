package com.hackathon.agent.investigation.repository;

import com.hackathon.agent.entity.TaskRunEntity;
import java.util.Optional;
import org.springframework.data.repository.Repository;

/**
 * Read-only lookup of TaskRuns stored by the collector. A name may be reused after a run was deleted, so the newest
 * row wins.
 */
public interface AgentTaskRunLookup extends Repository<TaskRunEntity, Long> {

    Optional<TaskRunEntity> findFirstByNamespaceAndTaskRunNameOrderByIdDesc(String namespace, String taskRunName);
}
