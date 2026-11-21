package com.hackathon.agent.service;

import com.hackathon.agent.exception.InvalidRequestException;
import java.util.Set;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * Restricts client-provided sort properties to an explicit allow-list so that invalid or internal
 * properties produce a clear 400 response.
 */
final class SortValidator {

    private SortValidator() {
    }

    static void validate(Pageable pageable, Set<String> allowed) {
        for (Sort.Order order : pageable.getSort()) {
            if (!allowed.contains(order.getProperty())) {
                throw new InvalidRequestException("Unsupported sort property '%s'. Allowed: %s"
                        .formatted(order.getProperty(), allowed.stream().sorted().toList()));
            }
        }
    }
}
