package com.hackathon.agent.repository;

import com.hackathon.agent.enums.ExecutionStatus;
import java.time.Instant;

/**
 * Optional filters shared by PipelineRun and TaskRun queries. Null values are ignored.
 * The time window applies to {@code startTime}: {@code from} inclusive, {@code to} exclusive.
 */
public record ExecutionFilter(
        String namespace,
        String pipelineName,
        ExecutionStatus status,
        Instant from,
        Instant to) {

    public static ExecutionFilter forWindow(Instant from, Instant to, String namespace, String pipelineName) {
        return new ExecutionFilter(namespace, pipelineName, null, from, to);
    }
}
