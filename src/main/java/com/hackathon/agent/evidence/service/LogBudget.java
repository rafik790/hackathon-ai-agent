package com.hackathon.agent.evidence.service;

import com.hackathon.agent.evidence.model.LogStatus;
import com.hackathon.agent.evidence.model.StoredAttempt;
import com.hackathon.agent.evidence.model.StoredLog;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

/**
 * Log limits of one TaskRun, shared by all its attempts and including what is already stored.
 */
class LogBudget {

    static final int MAX_CONTAINERS = 3;

    private final Set<String> containers = new HashSet<>();
    private int remainingBytes;

    LogBudget(int maxBytes, Collection<StoredAttempt> stored) {
        this.remainingBytes = maxBytes;
        for (StoredAttempt attempt : stored) {
            for (StoredLog log : attempt.logs()) {
                containers.add(key(attempt.attempt(), log.containerName()));
                if (log.status() == LogStatus.COLLECTED) {
                    remainingBytes -= log.storedBytes();
                }
            }
        }
        remainingBytes = Math.max(0, remainingBytes);
    }

    /** Reserves one of the containers allowed per TaskRun; a container already counted stays allowed. */
    boolean admit(int attempt, String container) {
        String key = key(attempt, container);
        if (!containers.contains(key) && containers.size() >= MAX_CONTAINERS) {
            return false;
        }
        containers.add(key);
        return true;
    }

    int remainingBytes() {
        return remainingBytes;
    }

    void use(int bytes) {
        remainingBytes = Math.max(0, remainingBytes - bytes);
    }

    private static String key(int attempt, String container) {
        return attempt + "/" + container;
    }
}
