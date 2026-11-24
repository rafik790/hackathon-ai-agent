package com.hackathon.agent.evidence.repository;

import com.hackathon.agent.entity.TaskRunEntity;
import com.hackathon.agent.evidence.model.FailedTaskRun;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * Read-only view on the Phase 1 TaskRuns that selects the ones needing evidence.
 */
public interface FailedTaskRunRepository extends Repository<TaskRunEntity, Long> {

    /** Recent failures without any evidence, newest first because their Pods are most likely to still exist. */
    @Query("""
            select new com.hackathon.agent.evidence.model.FailedTaskRun(t.id, t.taskRunUid, t.taskRunName, t.namespace)
            from TaskRunEntity t
            where t.status = com.hackathon.agent.enums.ExecutionStatus.FAILED
              and t.namespace in :namespaces
              and (t.completionTime >= :cutoff or (t.completionTime is null and t.createdAt >= :cutoff))
              and not exists (select 1 from FailureEvidenceEntity e where e.taskRunId = t.id)
            order by t.completionTime desc nulls last, t.id desc
            """)
    List<FailedTaskRun> findWithoutEvidence(@Param("cutoff") Instant cutoff,
                                            @Param("namespaces") Collection<String> namespaces, Limit limit);

    /** TaskRuns with pending evidence whose retry is due. */
    @Query("""
            select new com.hackathon.agent.evidence.model.FailedTaskRun(t.id, t.taskRunUid, t.taskRunName, t.namespace)
            from TaskRunEntity t
            where t.namespace in :namespaces
              and exists (select 1 from FailureEvidenceEntity e
                          where e.taskRunId = t.id
                            and e.status = com.hackathon.agent.evidence.model.EvidenceStatus.PENDING
                            and (e.nextAttemptAt is null or e.nextAttemptAt <= :now))
            order by t.completionTime desc nulls last, t.id desc
            """)
    List<FailedTaskRun> findDueForRetry(@Param("now") Instant now,
                                        @Param("namespaces") Collection<String> namespaces, Limit limit);
}
