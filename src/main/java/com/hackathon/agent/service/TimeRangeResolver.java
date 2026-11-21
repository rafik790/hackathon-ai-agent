package com.hackathon.agent.service;

import com.hackathon.agent.enums.TimeRangePreset;
import com.hackathon.agent.enums.TrendGranularity;
import com.hackathon.agent.exception.InvalidRequestException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.stereotype.Component;

/**
 * Turns dashboard query parameters into a {@link TimeRange}.
 * <ul>
 *   <li>{@code range=24h|7d|30d} selects a preset window ending now (default {@value #DEFAULT_RANGE}).</li>
 *   <li>{@code from} (and optionally {@code to}, default now) selects a custom window.</li>
 *   <li>Granularity defaults to HOUR for windows up to 48 hours and DAY otherwise.</li>
 * </ul>
 */
@Component
public class TimeRangeResolver {

    static final String DEFAULT_RANGE = "7d";
    static final Duration MAX_RANGE = Duration.ofDays(366);
    static final Duration HOURLY_THRESHOLD = Duration.ofHours(48);
    static final Duration MAX_HOURLY_RANGE = Duration.ofDays(31);

    private final Clock clock;

    public TimeRangeResolver(Clock clock) {
        this.clock = clock;
    }

    public TimeRange resolve(String range, Instant from, Instant to, TrendGranularity granularity) {
        Instant now = Instant.now(clock);
        Instant start;
        Instant end;
        if (from != null || to != null) {
            if (range != null && !range.isBlank()) {
                throw new InvalidRequestException("Specify either 'range' or 'from'/'to', not both");
            }
            if (from == null) {
                throw new InvalidRequestException("'from' is required when 'to' is specified");
            }
            start = from;
            end = to != null ? to : now;
        } else {
            TimeRangePreset preset;
            try {
                preset = TimeRangePreset.fromCode(range == null || range.isBlank() ? DEFAULT_RANGE : range.trim());
            } catch (IllegalArgumentException e) {
                throw new InvalidRequestException(e.getMessage());
            }
            end = now;
            start = now.minus(preset.duration());
        }

        if (!start.isBefore(end)) {
            throw new InvalidRequestException("'from' must be before 'to'");
        }
        Duration span = Duration.between(start, end);
        if (span.compareTo(MAX_RANGE) > 0) {
            throw new InvalidRequestException("Time range must not exceed " + MAX_RANGE.toDays() + " days");
        }
        TrendGranularity resolvedGranularity = granularity != null ? granularity
                : (span.compareTo(HOURLY_THRESHOLD) <= 0 ? TrendGranularity.HOUR : TrendGranularity.DAY);
        if (resolvedGranularity == TrendGranularity.HOUR && span.compareTo(MAX_HOURLY_RANGE) > 0) {
            throw new InvalidRequestException("Hourly granularity is limited to ranges of "
                    + MAX_HOURLY_RANGE.toDays() + " days; use granularity=day");
        }
        return new TimeRange(start, end, resolvedGranularity);
    }
}
