package com.hackathon.agent.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * AI failure analysis settings ({@code ai.*}), independent of the LLM provider. Disabled by default.
 *
 * @param enabled  whether failures are analysed; nothing is sent to any provider when false
 * @param provider name of the LLM provider to use
 */
@Validated
@ConfigurationProperties(prefix = "ai")
public record AiProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("anthropic") @NotBlank String provider,
        @DefaultValue @NotNull @Valid Analysis analysis) {

    /**
     * @param version                  increase to allow a new analysis of evidence analysed before
     * @param reanalyseOnVersionChange whether a new analysis or prompt version re-analyses evidence automatically
     * @param lookbackHours            only evidence collected within this window is analysed automatically
     * @param dailyLimit               analyses started automatically or manually within 24 hours
     * @param batchSize                analyses processed per cycle
     * @param pollingIntervalMs        delay between the end of one cycle and the start of the next
     * @param initialDelayMs           delay before the first cycle
     * @param maxConcurrentRequests    maximum concurrent provider requests
     * @param maxRetries               retries after the first attempt
     * @param retryBackoffSeconds      first retry delay; doubles with every further retry
     * @param leaseSeconds             after this time an unfinished analysis may be claimed again
     * @param maxLogChars              log text sent per analysis, over all containers
     */
    public record Analysis(
            @DefaultValue("1") @Min(1) int version,
            @DefaultValue("false") boolean reanalyseOnVersionChange,
            @DefaultValue("24") @Min(1) int lookbackHours,
            @DefaultValue("100") @Min(1) int dailyLimit,
            @DefaultValue("5") @Min(1) @Max(50) int batchSize,
            @DefaultValue("30000") @Min(1000) long pollingIntervalMs,
            @DefaultValue("15000") @Min(0) long initialDelayMs,
            @DefaultValue("2") @Min(1) @Max(10) int maxConcurrentRequests,
            @DefaultValue("3") @Min(0) @Max(10) int maxRetries,
            @DefaultValue("60") @Min(1) int retryBackoffSeconds,
            @DefaultValue("600") @Min(60) int leaseSeconds,
            @DefaultValue("60000") @Min(1000) @Max(400000) int maxLogChars) {

        /** Attempts including the first one. */
        public int maxAttempts() {
            return maxRetries + 1;
        }
    }
}
