package com.hackathon.agent.collector;

import static org.assertj.core.api.Assertions.assertThat;

import com.hackathon.agent.collector.TektonStatusMapper.StatusInfo;
import com.hackathon.agent.enums.ExecutionStatus;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class TektonStatusMapperTest {

    @ParameterizedTest(name = "status={0} reason={1} -> {2}")
    @CsvSource({
            "True,    Succeeded,            SUCCEEDED",
            "True,    Completed,            SUCCEEDED",
            "False,   Failed,               FAILED",
            "False,   PipelineRunTimeout,   FAILED",
            "False,   Cancelled,            FAILED",
            "False,   CouldntGetPipeline,   FAILED",
            "Unknown, Running,              RUNNING",
            "Unknown, Started,              RUNNING",
            "Unknown, ResolvingPipelineRef, RUNNING",
            "unknown, Running,              RUNNING"
    })
    void mapsSucceededCondition(String status, String reason, ExecutionStatus expected) {
        StatusInfo info = TektonStatusMapper.map(List.of(condition("Succeeded", status, reason, "msg")), true);

        assertThat(info.status()).isEqualTo(expected);
    }

    @Test
    void failureKeepsReasonAndMessage() {
        StatusInfo info = TektonStatusMapper.map(
                List.of(condition("Succeeded", "False", "Failed", "Tasks Completed: 2 (Failed: 1)")), true);

        assertThat(info.failureReason()).isEqualTo("Failed");
        assertThat(info.failureMessage()).isEqualTo("Tasks Completed: 2 (Failed: 1)");
    }

    @Test
    void successAndRunningDoNotCarryFailureDetails() {
        assertThat(TektonStatusMapper.map(List.of(condition("Succeeded", "True", "Succeeded", "All done")), true))
                .isEqualTo(new StatusInfo(ExecutionStatus.SUCCEEDED, null, null));
        assertThat(TektonStatusMapper.map(List.of(condition("Succeeded", "Unknown", "Running", "1 of 3")), true))
                .isEqualTo(new StatusInfo(ExecutionStatus.RUNNING, null, null));
    }

    @Test
    void missingConditionsAreNeverTreatedAsFailure() {
        assertThat(TektonStatusMapper.map(List.of(), true).status()).isEqualTo(ExecutionStatus.RUNNING);
        assertThat(TektonStatusMapper.map(List.of(), false).status()).isEqualTo(ExecutionStatus.UNKNOWN);
        assertThat(TektonStatusMapper.map(null, false).status()).isEqualTo(ExecutionStatus.UNKNOWN);
    }

    @Test
    void ignoresOtherConditionTypes() {
        StatusInfo info = TektonStatusMapper.map(List.of(condition("Ready", "False", "NotReady", "x")), false);

        assertThat(info.status()).isEqualTo(ExecutionStatus.UNKNOWN);
    }

    @Test
    void unexpectedConditionStatusFallsBackToStartedState() {
        StatusInfo info = TektonStatusMapper.map(List.of(condition("Succeeded", "Weird", null, null)), true);

        assertThat(info.status()).isEqualTo(ExecutionStatus.RUNNING);
    }

    @Test
    void truncatesVeryLongMessages() {
        String longMessage = "x".repeat(TektonStatusMapper.MAX_MESSAGE_LENGTH + 500);

        StatusInfo info = TektonStatusMapper.map(List.of(condition("Succeeded", "False", "Failed", longMessage)), true);

        assertThat(info.failureMessage()).hasSize(TektonStatusMapper.MAX_MESSAGE_LENGTH);
    }

    private static Map<String, Object> condition(String type, String status, String reason, String message) {
        Map<String, Object> condition = new HashMap<>();
        condition.put("type", type);
        condition.put("status", status);
        condition.put("reason", reason);
        condition.put("message", message);
        return condition;
    }
}
