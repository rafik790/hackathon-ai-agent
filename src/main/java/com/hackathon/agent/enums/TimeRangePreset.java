package com.hackathon.agent.enums;

import java.time.Duration;
import java.util.Arrays;

/**
 * Predefined time ranges accepted by the dashboard endpoints via the {@code range} parameter.
 */
public enum TimeRangePreset {
    LAST_24_HOURS("24h", Duration.ofHours(24)),
    LAST_7_DAYS("7d", Duration.ofDays(7)),
    LAST_30_DAYS("30d", Duration.ofDays(30));

    private final String code;
    private final Duration duration;

    TimeRangePreset(String code, Duration duration) {
        this.code = code;
        this.duration = duration;
    }

    public String code() {
        return code;
    }

    public Duration duration() {
        return duration;
    }

    public static TimeRangePreset fromCode(String code) {
        return Arrays.stream(values())
                .filter(preset -> preset.code.equalsIgnoreCase(code) || preset.name().equalsIgnoreCase(code))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unsupported range '" + code + "'. Supported values: 24h, 7d, 30d"));
    }
}
