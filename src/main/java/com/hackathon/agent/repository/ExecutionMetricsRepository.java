package com.hackathon.agent.repository;

import com.hackathon.agent.entity.ExecutionEntity;
import com.hackathon.agent.enums.ExecutionStatus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * Aggregate queries backing the reliability metrics. Works for both execution tables because they
 * share the {@link ExecutionEntity} columns. Aggregation happens in the database; only the trend
 * series loads per-row projections (start time, status, duration).
 */
@Repository
public class ExecutionMetricsRepository {

    private static final List<ExecutionStatus> COMPLETED = List.of(ExecutionStatus.SUCCEEDED, ExecutionStatus.FAILED);

    private final EntityManager entityManager;

    public ExecutionMetricsRepository(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    /** Number of executions per status; every status is present in the result (zero when absent). */
    public Map<ExecutionStatus, Long> countByStatus(Class<? extends ExecutionEntity> type, ExecutionFilter filter) {
        Where where = where(filter);
        TypedQuery<Object[]> query = entityManager.createQuery(
                "select e.status, count(e) from " + entityName(type) + " e" + where.clause() + " group by e.status",
                Object[].class);
        where.bind(query);
        Map<ExecutionStatus, Long> counts = new EnumMap<>(ExecutionStatus.class);
        for (ExecutionStatus status : ExecutionStatus.values()) {
            counts.put(status, 0L);
        }
        for (Object[] row : query.getResultList()) {
            counts.put((ExecutionStatus) row[0], ((Number) row[1]).longValue());
        }
        return counts;
    }

    /** Average duration of completed executions, empty when there is none. */
    public Optional<Double> averageCompletedDuration(Class<? extends ExecutionEntity> type, ExecutionFilter filter) {
        Where where = completedWithDuration(filter);
        TypedQuery<Double> query = entityManager.createQuery(
                "select avg(e.durationMs) from " + entityName(type) + " e" + where.clause(), Double.class);
        where.bind(query);
        return Optional.ofNullable(query.getSingleResult());
    }

    public long countCompletedWithDuration(Class<? extends ExecutionEntity> type, ExecutionFilter filter) {
        Where where = completedWithDuration(filter);
        TypedQuery<Long> query = entityManager.createQuery(
                "select count(e) from " + entityName(type) + " e" + where.clause(), Long.class);
        where.bind(query);
        return query.getSingleResult();
    }

    /**
     * Duration of the completed execution at the given 1-based rank when ordered by duration ascending.
     * Used for nearest-rank percentiles without loading all durations into memory.
     */
    public Optional<Long> completedDurationAtRank(Class<? extends ExecutionEntity> type, ExecutionFilter filter,
                                                  long rank) {
        if (rank < 1) {
            return Optional.empty();
        }
        Where where = completedWithDuration(filter);
        TypedQuery<Long> query = entityManager.createQuery(
                "select e.durationMs from " + entityName(type) + " e" + where.clause()
                        + " order by e.durationMs asc, e.id asc", Long.class);
        where.bind(query);
        query.setFirstResult(Math.toIntExact(rank - 1));
        query.setMaxResults(1);
        return query.getResultList().stream().findFirst();
    }

    /** Start time, status and duration of every execution in the window, ordered by start time. */
    public List<ExecutionSample> findSamples(Class<? extends ExecutionEntity> type, ExecutionFilter filter) {
        Where where = where(filter);
        TypedQuery<ExecutionSample> query = entityManager.createQuery(
                "select new " + ExecutionSample.class.getName() + "(e.startTime, e.status, e.durationMs) from "
                        + entityName(type) + " e" + where.clause() + " order by e.startTime asc",
                ExecutionSample.class);
        where.bind(query);
        return query.getResultList();
    }

    private static String entityName(Class<? extends ExecutionEntity> type) {
        return type.getSimpleName();
    }

    private static Where completedWithDuration(ExecutionFilter filter) {
        Where where = where(filter);
        where.add("e.status in :completedStatuses", "completedStatuses", COMPLETED);
        where.add("e.durationMs is not null");
        return where;
    }

    private static Where where(ExecutionFilter filter) {
        Where where = new Where();
        if (filter.from() != null) {
            where.add("e.startTime >= :from", "from", filter.from());
        }
        if (filter.to() != null) {
            where.add("e.startTime < :to", "to", filter.to());
        }
        if (filter.namespace() != null && !filter.namespace().isBlank()) {
            where.add("e.namespace = :namespace", "namespace", filter.namespace());
        }
        if (filter.pipelineName() != null && !filter.pipelineName().isBlank()) {
            where.add("e.pipelineName = :pipelineName", "pipelineName", filter.pipelineName());
        }
        if (filter.status() != null) {
            where.add("e.status = :status", "status", filter.status());
        }
        return where;
    }

    /** Collects JPQL predicates together with their bound parameters (never string-concatenated values). */
    private static final class Where {
        private final StringBuilder clause = new StringBuilder();
        private final Map<String, Object> parameters = new LinkedHashMap<>();

        void add(String predicate) {
            clause.append(clause.isEmpty() ? " where " : " and ").append(predicate);
        }

        void add(String predicate, String name, Object value) {
            add(predicate);
            parameters.put(name, value);
        }

        String clause() {
            return clause.toString();
        }

        void bind(TypedQuery<?> query) {
            parameters.forEach(query::setParameter);
        }
    }
}
