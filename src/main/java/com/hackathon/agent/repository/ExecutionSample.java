package com.hackathon.agent.repository;

import com.hackathon.agent.enums.ExecutionStatus;
import java.time.Instant;

/**
 * Lightweight projection used for trend bucketing.
 */
public record ExecutionSample(Instant startTime, ExecutionStatus status, Long durationMs) {
}
