package com.hackathon.agent.ai.repository;

import com.hackathon.agent.ai.entity.FailureAnalysisEntity;
import com.hackathon.agent.ai.model.AnalysisStatus;
import com.hackathon.agent.ai.model.FailureCategory;
import com.hackathon.agent.ai.model.Severity;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FailureAnalysisRepository extends JpaRepository<FailureAnalysisEntity, Long> {

    List<FailureAnalysisEntity> findByFailureEvidenceIdOrderByIdDesc(Long failureEvidenceId);

    Optional<FailureAnalysisEntity> findByIdAndClaimToken(Long id, String claimToken);

    /** Analyses that may be claimed now: new ones, due retries and those whose worker disappeared. */
    @Query("""
            select a.id from FailureAnalysisEntity a
            where a.analysisStatus = com.hackathon.agent.ai.model.AnalysisStatus.PENDING
               or (a.analysisStatus = com.hackathon.agent.ai.model.AnalysisStatus.RETRY_PENDING and a.nextAttemptAt <= :now)
               or (a.analysisStatus = com.hackathon.agent.ai.model.AnalysisStatus.PROCESSING and a.lockedUntil < :now)
            order by a.id
            """)
    List<Long> findClaimable(@Param("now") Instant now, Limit limit);

    /**
     * Claims an analysis. The conditions are evaluated again under the row lock of the update, so only one of
     * several concurrent workers, in this or another instance, gets a result of 1.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update FailureAnalysisEntity a
            set a.analysisStatus = com.hackathon.agent.ai.model.AnalysisStatus.PROCESSING, a.claimToken = :token,
                a.lockedUntil = :lockedUntil, a.startedAt = :now, a.nextAttemptAt = null,
                a.processingAttempts = a.processingAttempts + 1, a.updatedAt = :now
            where a.id = :id and a.processingAttempts < :maxAttempts
              and (a.analysisStatus = com.hackathon.agent.ai.model.AnalysisStatus.PENDING
                or (a.analysisStatus = com.hackathon.agent.ai.model.AnalysisStatus.RETRY_PENDING and a.nextAttemptAt <= :now)
                or (a.analysisStatus = com.hackathon.agent.ai.model.AnalysisStatus.PROCESSING and a.lockedUntil < :now))
            """)
    int claim(@Param("id") Long id, @Param("token") String token, @Param("now") Instant now,
              @Param("lockedUntil") Instant lockedUntil, @Param("maxAttempts") int maxAttempts);

    /** Gives up analyses without attempts left: abandoned by their worker, or after the limit was lowered. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update FailureAnalysisEntity a
            set a.analysisStatus = com.hackathon.agent.ai.model.AnalysisStatus.FAILED, a.claimToken = null,
                a.lockedUntil = null, a.nextAttemptAt = null, a.updatedAt = :now,
                a.lastError = coalesce(a.lastError, 'Processing was interrupted on the last attempt')
            where a.processingAttempts >= :maxAttempts
              and (a.analysisStatus = com.hackathon.agent.ai.model.AnalysisStatus.RETRY_PENDING
                or (a.analysisStatus = com.hackathon.agent.ai.model.AnalysisStatus.PROCESSING and a.lockedUntil < :now))
            """)
    int failAbandoned(@Param("now") Instant now, @Param("maxAttempts") int maxAttempts);

    /**
     * Recently collected evidence that still needs an analysis.
     *
     * @param anyVersion true to skip evidence analysed with any version, false to only consider the given one
     */
    @Query("""
            select e.id from FailureEvidenceEntity e
            where e.status in (com.hackathon.agent.evidence.model.EvidenceStatus.COLLECTED,
                               com.hackathon.agent.evidence.model.EvidenceStatus.PARTIAL,
                               com.hackathon.agent.evidence.model.EvidenceStatus.NO_POD,
                               com.hackathon.agent.evidence.model.EvidenceStatus.POD_DELETED)
              and e.collectedAt >= :cutoff
              and not exists (select 1 from FailureAnalysisEntity a
                              where a.failureEvidenceId = e.id
                                and (:anyVersion = true
                                     or (a.analysisVersion = :analysisVersion and a.promptVersion = :promptVersion)))
            order by e.id desc
            """)
    List<Long> findEvidenceToAnalyse(@Param("cutoff") Instant cutoff, @Param("anyVersion") boolean anyVersion,
                                     @Param("analysisVersion") int analysisVersion,
                                     @Param("promptVersion") String promptVersion, Limit limit);

    /** An analysis together with the execution it belongs to. */
    record AnalysisView(FailureAnalysisEntity analysis, int attempt, Long taskRunId, String taskRunName,
                        String taskName, String pipelineRunName, String namespace) {
    }

    String VIEW = """
            select new com.hackathon.agent.ai.repository.FailureAnalysisRepository$AnalysisView(
                a, e.attempt, t.id, t.taskRunName, t.taskName, t.pipelineRunName, t.namespace)
            from FailureAnalysisEntity a
            join FailureEvidenceEntity e on e.id = a.failureEvidenceId
            join TaskRunEntity t on t.id = e.taskRunId
            """;

    @Query(value = VIEW + """
            where (:category is null or a.failureCategory = :category)
              and (:severity is null or a.severity = :severity)
              and (:status is null or a.analysisStatus = :status)
            """, countQuery = """
            select count(a) from FailureAnalysisEntity a
            where (:category is null or a.failureCategory = :category)
              and (:severity is null or a.severity = :severity)
              and (:status is null or a.analysisStatus = :status)
            """)
    Page<AnalysisView> search(@Param("category") FailureCategory category, @Param("severity") Severity severity,
                              @Param("status") AnalysisStatus status, Pageable pageable);

    @Query(VIEW + " where a.id = :id")
    Optional<AnalysisView> findView(@Param("id") Long id);

    /** Analyses of a TaskRun: last attempt first, newest analysis of an attempt first. */
    @Query(VIEW + " where t.id = :taskRunId order by e.attempt desc, a.id desc")
    List<AnalysisView> findViewsByTaskRun(@Param("taskRunId") Long taskRunId, Limit limit);
}
