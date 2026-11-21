package com.hackathon.agent.repository;

import com.hackathon.agent.entity.TaskRunEntity;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TaskRunRepository
        extends JpaRepository<TaskRunEntity, Long>, JpaSpecificationExecutor<TaskRunEntity> {

    Optional<TaskRunEntity> findByTaskRunUid(String taskRunUid);

    List<TaskRunEntity> findByTaskRunUidIn(Collection<String> taskRunUids);

    /**
     * TaskRuns belonging to a PipelineRun. Matches on the owner UID; falls back to namespace + name for
     * TaskRuns whose owner reference was not available when they were collected.
     */
    @Query("""
            select t from TaskRunEntity t
            where t.pipelineRunUid = :pipelineRunUid
               or (t.pipelineRunUid is null and t.namespace = :namespace and t.pipelineRunName = :pipelineRunName)
            order by t.startTime asc nulls last, t.id asc
            """)
    List<TaskRunEntity> findByPipelineRun(@Param("pipelineRunUid") String pipelineRunUid,
                                          @Param("namespace") String namespace,
                                          @Param("pipelineRunName") String pipelineRunName);
}
