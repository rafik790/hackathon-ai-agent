package com.hackathon.agent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hackathon.agent.enums.TrendGranularity;
import com.hackathon.agent.exception.InvalidRequestException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class TimeRangeResolverTest {

    private static final Instant NOW = Instant.parse("2026-11-21T12:30:00Z");

    private final TimeRangeResolver resolver = new TimeRangeResolver(Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void defaultsToLastSevenDaysWithDailyBuckets() {
        TimeRange range = resolver.resolve(null, null, null, null);

        assertThat(range.from()).isEqualTo(Instant.parse("2026-11-14T12:30:00Z"));
        assertThat(range.to()).isEqualTo(NOW);
        assertThat(range.granularity()).isEqualTo(TrendGranularity.DAY);
    }

    @Test
    void last24HoursUsesHourlyBuckets() {
        TimeRange range = resolver.resolve("24h", null, null, null);

        assertThat(range.from()).isEqualTo(NOW.minusSeconds(24 * 3600));
        assertThat(range.granularity()).isEqualTo(TrendGranularity.HOUR);
    }

    @Test
    void last30Days() {
        assertThat(resolver.resolve("30d", null, null, null).from()).isEqualTo(Instant.parse("2026-10-22T12:30:00Z"));
    }

    @Test
    void customRangeDefaultsEndToNow() {
        Instant from = Instant.parse("2026-11-21T00:00:00Z");

        TimeRange range = resolver.resolve(null, from, null, null);

        assertThat(range.from()).isEqualTo(from);
        assertThat(range.to()).isEqualTo(NOW);
        assertThat(range.granularity()).isEqualTo(TrendGranularity.HOUR);
    }

    @Test
    void explicitGranularityWins() {
        assertThat(resolver.resolve("24h", null, null, TrendGranularity.DAY).granularity()).isEqualTo(TrendGranularity.DAY);
    }

    @Test
    void rejectsInvalidInput() {
        assertThatThrownBy(() -> resolver.resolve("1y", null, null, null))
                .isInstanceOf(InvalidRequestException.class).hasMessageContaining("Unsupported range");
        assertThatThrownBy(() -> resolver.resolve(null, NOW, NOW.minusSeconds(1), null))
                .isInstanceOf(InvalidRequestException.class).hasMessageContaining("before");
        assertThatThrownBy(() -> resolver.resolve(null, null, NOW, null))
                .isInstanceOf(InvalidRequestException.class).hasMessageContaining("'from' is required");
        assertThatThrownBy(() -> resolver.resolve("7d", NOW.minusSeconds(60), null, null))
                .isInstanceOf(InvalidRequestException.class).hasMessageContaining("either");
        assertThatThrownBy(() -> resolver.resolve(null, Instant.parse("2024-01-01T00:00:00Z"), NOW, null))
                .isInstanceOf(InvalidRequestException.class).hasMessageContaining("366");
        assertThatCode(() -> resolver.resolve("30d", null, null, TrendGranularity.HOUR))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> resolver.resolve(null, Instant.parse("2026-09-01T00:00:00Z"), NOW, TrendGranularity.HOUR))
                .isInstanceOf(InvalidRequestException.class).hasMessageContaining("Hourly");
    }
}
