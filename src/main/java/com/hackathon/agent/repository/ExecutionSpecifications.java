package com.hackathon.agent.repository;

import com.hackathon.agent.entity.ExecutionEntity;
import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.jpa.domain.Specification;

/**
 * Builds JPA specifications for the paginated list endpoints.
 */
public final class ExecutionSpecifications {

    private ExecutionSpecifications() {
    }

    public static <T extends ExecutionEntity> Specification<T> matching(ExecutionFilter filter) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (hasText(filter.namespace())) {
                predicates.add(cb.equal(root.get("namespace"), filter.namespace()));
            }
            if (hasText(filter.pipelineName())) {
                predicates.add(cb.equal(root.get("pipelineName"), filter.pipelineName()));
            }
            if (filter.status() != null) {
                predicates.add(cb.equal(root.get("status"), filter.status()));
            }
            if (filter.from() != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("startTime"), filter.from()));
            }
            if (filter.to() != null) {
                predicates.add(cb.lessThan(root.get("startTime"), filter.to()));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }

    public static <T extends ExecutionEntity> Specification<T> attributeEquals(String attribute, String value) {
        return (root, query, cb) -> hasText(value) ? cb.equal(root.get(attribute), value) : cb.conjunction();
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
