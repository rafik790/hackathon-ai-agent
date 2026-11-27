package com.hackathon.agent.investigation;

import com.hackathon.agent.evidence.sanitizer.LogSanitizer;
import com.hackathon.agent.investigation.agent.AgentDecisionParser;
import com.hackathon.agent.investigation.agent.AgentPromptBuilder;
import com.hackathon.agent.investigation.agent.AgentReportValidator;
import com.hackathon.agent.investigation.tool.AgentToolRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Agent components without dependencies on Spring. Nothing of the feature exists while it is disabled, except its
 * tables and its REST controller, which then reports that the feature is disabled.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnBooleanProperty(name = "agent.investigation.enabled")
public class AgentInvestigationConfig {

    @Bean
    AgentDecisionParser agentDecisionParser() {
        return new AgentDecisionParser();
    }

    @Bean
    AgentReportValidator agentReportValidator(LogSanitizer sanitizer) {
        return new AgentReportValidator(sanitizer);
    }

    @Bean
    AgentPromptBuilder agentPromptBuilder(AgentToolRegistry registry) {
        return new AgentPromptBuilder(registry.tools());
    }
}
