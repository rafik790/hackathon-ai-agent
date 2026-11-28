package com.hackathon.agent.resolution.repository;

import com.hackathon.agent.enums.ExecutionStatus;
import com.hackathon.agent.resolution.model.ResolutionStatus;
import com.hackathon.agent.resolution.model.ResolutionTargetType;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import java.time.Instant;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Repository;

/**
 * Resolution status of the failed TaskRuns of a time window, counted in one grouped query. Uses the window, namespace
 * and pipeline predicates of {@code ExecutionMetricsRepository}, so the counts add up to the failed TaskRuns of the
 * dashboard summary.
 */
@Repository
public class ResolutionCountRepository {

    private final EntityManager entityManager;

    public ResolutionCountRepository(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    /** Every status is present (zero when absent); a failed TaskRun without a record counts as OPEN. */
    public Map<ResolutionStatus, Long> failedTaskRunsByResolution(Instant from, Instant to, String namespace, String pipelineName) {
        StringBuilder where = new StringBuilder(" where t.status = :failed");
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("failed", ExecutionStatus.FAILED);
        parameters.put("taskRun", ResolutionTargetType.TASK_RUN);
        if (from != null) {
            where.append(" and t.startTime >= :from");
            parameters.put("from", from);
        }
        if (to != null) {
            where.append(" and t.startTime < :to");
            parameters.put("to", to);
        }
        if (namespace != null && !namespace.isBlank()) {
            where.append(" and t.namespace = :namespace");
            parameters.put("namespace", namespace);
        }
        if (pipelineName != null && !pipelineName.isBlank()) {
            where.append(" and t.pipelineName = :pipelineName");
            parameters.put("pipelineName", pipelineName);
        }
        TypedQuery<Object[]> query = entityManager.createQuery(
                "select r.status, count(t) from TaskRunEntity t left join ResolutionRecordEntity r"
                        + " on r.taskRunId = t.id and r.targetType = :taskRun" + where + " group by r.status",
                Object[].class);
        parameters.forEach(query::setParameter);

        Map<ResolutionStatus, Long> counts = new EnumMap<>(ResolutionStatus.class);
        for (ResolutionStatus status : ResolutionStatus.values()) {
            counts.put(status, 0L);
        }
        for (Object[] row : query.getResultList()) {
            ResolutionStatus status = row[0] == null ? ResolutionStatus.OPEN : (ResolutionStatus) row[0];
            counts.merge(status, ((Number) row[1]).longValue(), Long::sum);
        }
        return counts;
    }
}
