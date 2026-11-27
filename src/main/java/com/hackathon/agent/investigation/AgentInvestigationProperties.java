package com.hackathon.agent.investigation;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Agent investigation settings ({@code agent.investigation.*}). Disabled by default; it also needs AI analysis
 * ({@code ai.enabled}), whose LLM provider and quota it uses.
 *
 * @param maxSteps           questions to the model per investigation, including the one for the final report
 * @param maxToolCalls       executed tools per investigation
 * @param timeoutSeconds     total duration of an investigation
 * @param maxToolOutputChars text of one tool result passed to the model and stored
 * @param maxLogChars        log text returned by the log tool, over all containers
 * @param maxPromptChars     size of one prompt; older tool results are shortened beyond it
 * @param pollIntervalMs     how often the worker looks for new investigations
 * @param leaseSeconds       after this time a running investigation is considered abandoned
 */
@Validated
@ConfigurationProperties(prefix = "agent.investigation")
public record AgentInvestigationProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("8") @Min(1) @Max(20) int maxSteps,
        @DefaultValue("10") @Min(1) @Max(30) int maxToolCalls,
        @DefaultValue("300") @Min(10) @Max(3600) int timeoutSeconds,
        @DefaultValue("6000") @Min(500) @Max(50000) int maxToolOutputChars,
        @DefaultValue("8000") @Min(500) @Max(60000) int maxLogChars,
        @DefaultValue("60000") @Min(5000) @Max(400000) int maxPromptChars,
        @DefaultValue("2000") @Min(200) long pollIntervalMs,
        @DefaultValue("900") @Min(60) int leaseSeconds) {

    public AgentInvestigationProperties {
        if (leaseSeconds <= timeoutSeconds) {
            throw new IllegalArgumentException("agent.investigation.lease-seconds must exceed timeout-seconds");
        }
    }
}
