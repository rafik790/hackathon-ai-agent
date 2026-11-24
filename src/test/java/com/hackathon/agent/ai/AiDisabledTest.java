package com.hackathon.agent.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hackathon.agent.ai.llm.LlmProvider;
import com.hackathon.agent.ai.scheduler.FailureAnalysisScheduler;
import com.hackathon.agent.ai.service.FailureAnalysisService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * With AI disabled nothing exists that could call a provider, even when credentials are configured.
 */
@SpringBootTest(properties = {"ai.enabled=false", "ai.provider=anthropic", "ai.anthropic.api-key=placeholder",
        "ai.anthropic.model=some-model"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AiDisabledTest {

    @Autowired
    ApplicationContext context;

    @Autowired
    MockMvc mvc;

    @Test
    void noProviderSchedulerOrWorkerExists() {
        assertThat(context.getBeansOfType(LlmProvider.class)).isEmpty();
        assertThat(context.getBeansOfType(FailureAnalysisScheduler.class)).isEmpty();
        assertThat(context.getBeansOfType(FailureAnalysisService.class)).isEmpty();
    }

    @Test
    void apiStaysReadableButAcceptsNoRequests() throws Exception {
        mvc.perform(get("/api/v1/failure-analyses")).andExpect(status().isOk());
        mvc.perform(post("/api/v1/failure-evidence/1/analyse")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("AI analysis is disabled"));
        mvc.perform(get("/actuator/health")).andExpect(status().isOk())
                .andExpect(jsonPath("$.components.aiAnalysis.details.state").value("DISABLED"))
                .andExpect(jsonPath("$.components.aiAnalysis.status").value("UP"));
    }
}
