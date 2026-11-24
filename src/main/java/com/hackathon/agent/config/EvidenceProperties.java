package com.hackathon.agent.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Failure evidence collection settings ({@code evidence.*}). Disabled by default.
 *
 * @param enabled                  whether the evidence worker runs (also requires {@code monitoring.enabled})
 * @param pollingIntervalMs        interval between collection cycles; a running cycle is never overlapped
 * @param initialDelayMs           delay before the first cycle
 * @param lookbackHours            only failures completed within this window are picked up
 * @param batchSize                maximum TaskRuns processed per cycle
 * @param maxConcurrentLogRequests maximum concurrent log requests
 * @param maxLogLines              lines requested from the end of each log
 * @param maxContainerLogBytes     retained bytes per log (bounded by the V2 column, VARCHAR(65536))
 * @param maxTaskrunLogBytes       retained log bytes per TaskRun, over all containers and attempts
 * @param retentionDays            days until a stored log expires
 * @param maxFetchAttempts         collection attempts before evidence becomes final
 * @param retryBackoffSeconds      first retry delay; doubles with every further attempt
 * @param requestTimeoutMs         timeout of a single evidence API request, independent of Phase 1
 */
@Validated
@ConfigurationProperties(prefix = "evidence")
public record EvidenceProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("30000") @Min(1000) long pollingIntervalMs,
        @DefaultValue("20000") @Min(0) long initialDelayMs,
        @DefaultValue("24") @Min(1) int lookbackHours,
        @DefaultValue("10") @Min(1) @Max(100) int batchSize,
        @DefaultValue("2") @Min(1) @Max(10) int maxConcurrentLogRequests,
        @DefaultValue("500") @Min(1) int maxLogLines,
        @DefaultValue("65536") @Min(1024) @Max(65536) int maxContainerLogBytes,
        @DefaultValue("262144") @Min(1024) int maxTaskrunLogBytes,
        @DefaultValue("14") @Min(1) int retentionDays,
        @DefaultValue("5") @Min(1) @Max(20) int maxFetchAttempts,
        @DefaultValue("60") @Min(1) int retryBackoffSeconds,
        @DefaultValue("10000") @Min(100) int requestTimeoutMs) {
}
