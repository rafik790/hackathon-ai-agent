package com.hackathon.agent.evidence.model;

import java.util.List;

/**
 * Previously persisted evidence of one attempt, used to avoid fetching anything twice.
 *
 * @param podSeen whether Pod details were already stored
 */
public record StoredAttempt(int attempt, EvidenceStatus status, int fetchAttempts, boolean podSeen,
                            List<StoredLog> logs) {
}
