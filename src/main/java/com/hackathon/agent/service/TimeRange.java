package com.hackathon.agent.service;

import com.hackathon.agent.enums.TrendGranularity;
import java.time.Instant;

/**
 * Resolved, validated time window: {@code from} inclusive, {@code to} exclusive, both UTC.
 */
public record TimeRange(Instant from, Instant to, TrendGranularity granularity) {
}
