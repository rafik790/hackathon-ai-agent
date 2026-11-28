package com.hackathon.agent.resolution;

import static org.assertj.core.api.Assertions.assertThat;

import com.hackathon.agent.resolution.model.ResolutionStatus;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

/** The transition table of the resolution lifecycle. */
class ResolutionStatusTest {

    @ParameterizedTest
    @CsvSource({
            "OPEN, INVESTIGATING, true", "OPEN, FIX_IN_PROGRESS, true", "OPEN, RESOLVED, false", "OPEN, REOPENED, false",
            "INVESTIGATING, FIX_IN_PROGRESS, true", "INVESTIGATING, RESOLVED, true", "INVESTIGATING, OPEN, false",
            "INVESTIGATING, REOPENED, false",
            "FIX_IN_PROGRESS, RESOLVED, true", "FIX_IN_PROGRESS, INVESTIGATING, false", "FIX_IN_PROGRESS, OPEN, false",
            "FIX_IN_PROGRESS, REOPENED, false",
            "RESOLVED, REOPENED, true", "RESOLVED, OPEN, false", "RESOLVED, INVESTIGATING, false", "RESOLVED, FIX_IN_PROGRESS, false",
            "REOPENED, INVESTIGATING, true", "REOPENED, FIX_IN_PROGRESS, true", "REOPENED, RESOLVED, false", "REOPENED, OPEN, false"})
    void allowsOnlyTheDocumentedTransitions(ResolutionStatus from, ResolutionStatus to, boolean allowed) {
        assertThat(from.canChangeTo(to)).isEqualTo(allowed);
        assertThat(from.nextStatuses().contains(to)).isEqualTo(allowed);
    }

    @ParameterizedTest
    @EnumSource(ResolutionStatus.class)
    void keepingTheStatusIsAlwaysAllowedButNoStatusLeadsBackToOpen(ResolutionStatus status) {
        assertThat(status.canChangeTo(status)).isTrue();
        assertThat(status.nextStatuses()).doesNotContain(ResolutionStatus.OPEN, status);
    }
}
