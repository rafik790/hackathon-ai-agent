package com.hackathon.agent.collector;

import com.hackathon.agent.enums.ExecutionStatus;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Maps Tekton status conditions to {@link ExecutionStatus}.
 * <p>
 * Tekton reports the outcome of a run through the condition of type {@code Succeeded}:
 * <ul>
 *   <li>{@code status=True} &rarr; SUCCEEDED</li>
 *   <li>{@code status=False} &rarr; FAILED (includes failures, timeouts and cancellations; the reason is kept)</li>
 *   <li>{@code status=Unknown} &rarr; RUNNING (in progress, pending or resolving references)</li>
 *   <li>no condition yet &rarr; RUNNING when a start time exists, otherwise UNKNOWN</li>
 * </ul>
 * An incomplete status never results in FAILED.
 */
public final class TektonStatusMapper {

    static final String SUCCEEDED_CONDITION = "Succeeded";
    static final int MAX_REASON_LENGTH = 255;
    static final int MAX_MESSAGE_LENGTH = 4000;

    private TektonStatusMapper() {
    }

    public record StatusInfo(ExecutionStatus status, String failureReason, String failureMessage) {
    }

    public static StatusInfo map(List<Map<String, Object>> conditions, boolean started) {
        Map<String, Object> succeeded = findSucceededCondition(conditions);
        if (succeeded == null) {
            return new StatusInfo(started ? ExecutionStatus.RUNNING : ExecutionStatus.UNKNOWN, null, null);
        }
        String conditionStatus = asString(succeeded.get("status"));
        if ("True".equalsIgnoreCase(conditionStatus)) {
            return new StatusInfo(ExecutionStatus.SUCCEEDED, null, null);
        }
        if ("False".equalsIgnoreCase(conditionStatus)) {
            return new StatusInfo(ExecutionStatus.FAILED,
                    truncate(asString(succeeded.get("reason")), MAX_REASON_LENGTH),
                    truncate(asString(succeeded.get("message")), MAX_MESSAGE_LENGTH));
        }
        if ("Unknown".equalsIgnoreCase(conditionStatus)) {
            return new StatusInfo(ExecutionStatus.RUNNING, null, null);
        }
        return new StatusInfo(started ? ExecutionStatus.RUNNING : ExecutionStatus.UNKNOWN, null, null);
    }

    private static Map<String, Object> findSucceededCondition(List<Map<String, Object>> conditions) {
        if (conditions == null) {
            return null;
        }
        return conditions.stream()
                .filter(Objects::nonNull)
                .filter(condition -> SUCCEEDED_CONDITION.equals(condition.get("type")))
                .findFirst()
                .orElse(null);
    }

    private static String asString(Object value) {
        return value == null ? null : value.toString();
    }

    static String truncate(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
