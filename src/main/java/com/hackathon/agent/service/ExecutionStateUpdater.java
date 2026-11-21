package com.hackathon.agent.service;

import com.hackathon.agent.collector.ExecutionState;
import com.hackathon.agent.entity.ExecutionEntity;
import java.time.Instant;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Applies a freshly collected {@link ExecutionState} to a persisted execution record.
 */
final class ExecutionStateUpdater {

    private ExecutionStateUpdater() {
    }

    /**
     * Copies the execution state onto the entity.
     * <p>
     * A record that already reached a terminal status (SUCCEEDED/FAILED) is never moved back to a
     * non-terminal status, protecting history against transiently incomplete API responses.
     *
     * @return {@code true} if any column changed
     */
    static boolean apply(ExecutionEntity entity, ExecutionState state) {
        if (entity.getStatus() != null && entity.getStatus().isTerminal() && !state.status().isTerminal()) {
            return false;
        }
        boolean changed = false;
        if (entity.getStatus() != state.status()) {
            entity.setStatus(state.status());
            changed = true;
        }
        if (!Objects.equals(entity.getStartTime(), state.startTime())) {
            entity.setStartTime(state.startTime());
            changed = true;
        }
        if (!Objects.equals(entity.getCompletionTime(), state.completionTime())) {
            entity.setCompletionTime(state.completionTime());
            changed = true;
        }
        if (!Objects.equals(entity.getDurationMs(), state.durationMs())) {
            entity.setDurationMs(state.durationMs());
            changed = true;
        }
        if (!Objects.equals(entity.getFailureReason(), state.failureReason())) {
            entity.setFailureReason(state.failureReason());
            changed = true;
        }
        if (!Objects.equals(entity.getFailureMessage(), state.failureMessage())) {
            entity.setFailureMessage(state.failureMessage());
            changed = true;
        }
        return changed;
    }

    static <T> boolean setIfChanged(T current, T next, Consumer<T> setter) {
        if (Objects.equals(current, next)) {
            return false;
        }
        setter.accept(next);
        return true;
    }

    static void touch(ExecutionEntity entity, Instant now, boolean isNew) {
        if (isNew) {
            entity.setCreatedAt(now);
        }
        entity.setUpdatedAt(now);
    }
}
