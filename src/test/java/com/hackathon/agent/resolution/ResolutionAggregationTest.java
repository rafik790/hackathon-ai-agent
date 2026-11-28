package com.hackathon.agent.resolution;

import static org.assertj.core.api.Assertions.assertThat;

import com.hackathon.agent.resolution.model.ResolutionAggregation;
import com.hackathon.agent.resolution.model.ResolutionStatus;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** PipelineRun resolution derived from the resolutions of its failed TaskRuns. */
class ResolutionAggregationTest {

    @Test
    void withoutFailedTaskRunsThereIsNothingToResolve() {
        assertThat(ResolutionAggregation.aggregate(List.of())).isNull();
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(delimiter = '|', value = {
            "OPEN                                   | OPEN",
            "OPEN OPEN                              | OPEN",
            "RESOLVED                               | RESOLVED",
            "RESOLVED RESOLVED RESOLVED             | RESOLVED",
            "RESOLVED OPEN                          | OPEN",
            "OPEN INVESTIGATING                     | INVESTIGATING",
            "RESOLVED INVESTIGATING OPEN            | INVESTIGATING",
            "RESOLVED FIX_IN_PROGRESS RESOLVED      | FIX_IN_PROGRESS",
            "INVESTIGATING FIX_IN_PROGRESS OPEN     | FIX_IN_PROGRESS",
            "RESOLVED REOPENED RESOLVED             | REOPENED",
            "REOPENED FIX_IN_PROGRESS INVESTIGATING | REOPENED"})
    void takesTheMostPressingStatusAndResolvedOnlyWhenAllAre(String statuses, ResolutionStatus expected) {
        List<ResolutionStatus> taskRuns = Arrays.stream(statuses.trim().split("\\s+")).map(ResolutionStatus::valueOf).toList();

        assertThat(ResolutionAggregation.aggregate(taskRuns)).isEqualTo(expected);
    }

    @Test
    void theOrderOfTheTaskRunsDoesNotMatter() {
        List<ResolutionStatus> statuses = List.of(ResolutionStatus.RESOLVED, ResolutionStatus.OPEN, ResolutionStatus.FIX_IN_PROGRESS);

        assertThat(ResolutionAggregation.aggregate(statuses)).isEqualTo(ResolutionAggregation.aggregate(statuses.reversed()))
                .isEqualTo(ResolutionStatus.FIX_IN_PROGRESS);
    }
}
