package com.hackathon.agent.collector;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class DurationCalculatorTest {

    private static final Instant START = Instant.parse("2026-11-21T10:00:00Z");

    @Test
    void computesMillisecondsBetweenStartAndCompletion() {
        assertThat(DurationCalculator.durationMs(START, Instant.parse("2026-11-21T10:03:15.250Z"))).isEqualTo(195_250L);
    }

    @Test
    void zeroForInstantCompletion() {
        assertThat(DurationCalculator.durationMs(START, START)).isZero();
    }

    @Test
    void nullWhileRunningOrNotStarted() {
        assertThat(DurationCalculator.durationMs(START, null)).isNull();
        assertThat(DurationCalculator.durationMs(null, START)).isNull();
        assertThat(DurationCalculator.durationMs(null, null)).isNull();
    }

    @Test
    void clampsNegativeDurationCausedByClockSkew() {
        assertThat(DurationCalculator.durationMs(START, START.minusSeconds(2))).isZero();
    }
}
