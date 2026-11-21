package com.hackathon.agent.enums;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Bucket size used for dashboard trend series. Buckets are always aligned to UTC.
 */
public enum TrendGranularity {
    HOUR(ChronoUnit.HOURS),
    DAY(ChronoUnit.DAYS);

    private final ChronoUnit unit;

    TrendGranularity(ChronoUnit unit) {
        this.unit = unit;
    }

    public Instant truncate(Instant instant) {
        return instant.truncatedTo(unit);
    }

    public Duration step() {
        return unit.getDuration();
    }
}
