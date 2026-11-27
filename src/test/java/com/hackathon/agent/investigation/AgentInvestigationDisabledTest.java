package com.hackathon.agent.investigation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hackathon.agent.investigation.service.AgentInvestigationRunner;
import com.hackathon.agent.investigation.service.AgentInvestigationService;
import com.hackathon.agent.investigation.service.AgentInvestigationWorker;
import com.hackathon.agent.investigation.tool.AgentKubernetesReader;
import com.hackathon.agent.investigation.tool.AgentTool;
import com.hackathon.agent.investigation.tool.AgentToolRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * By default the feature does not exist, even with AI analysis enabled, and its API says so.
 */
@SpringBootTest(properties = {"ai.enabled=true", "ai.provider=mock", "ai.analysis.initial-delay-ms=86400000"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AgentInvestigationDisabledTest {

    @Autowired
    ApplicationContext context;

    @Autowired
    MockMvc mvc;

    @Test
    void nothingOfTheAgentExists() {
        assertThat(context.getBeansOfType(AgentInvestigationService.class)).isEmpty();
        assertThat(context.getBeansOfType(AgentInvestigationWorker.class)).isEmpty();
        assertThat(context.getBeansOfType(AgentInvestigationRunner.class)).isEmpty();
        assertThat(context.getBeansOfType(AgentToolRegistry.class)).isEmpty();
        assertThat(context.getBeansOfType(AgentTool.class)).isEmpty();
        assertThat(context.getBeansOfType(AgentKubernetesReader.class)).isEmpty();
    }

    @Test
    void apiExplainsThatTheFeatureIsDisabled() throws Exception {
        String message = "Agent investigation is disabled; set AGENT_INVESTIGATION_ENABLED=true to use it";
        mvc.perform(post("/api/v1/agent-investigations").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"taskRunName\": \"x\", \"namespace\": \"ci\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.message").value(message));
        mvc.perform(get("/api/v1/agent-investigations")).andExpect(status().isConflict()).andExpect(jsonPath("$.message").value(message));
        mvc.perform(get("/api/v1/agent-investigations/1")).andExpect(status().isConflict());
    }

    @Test
    void existingAnalysisApiIsUnaffected() throws Exception {
        mvc.perform(get("/api/v1/failure-analyses")).andExpect(status().isOk());
    }
}
