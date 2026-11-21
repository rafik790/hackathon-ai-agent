package com.hackathon.agent;

import static com.hackathon.agent.support.TektonFixtures.pipelineRun;
import static com.hackathon.agent.support.TektonFixtures.taskRun;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hackathon.agent.collector.TektonCollectionService;
import com.hackathon.agent.kubernetes.KubernetesAccessException;
import com.hackathon.agent.kubernetes.TektonResourceClient;
import com.hackathon.agent.repository.PipelineRunRepository;
import com.hackathon.agent.repository.TaskRunRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * End-to-end through the full application context: collection cycle (Kubernetes mocked) &rarr;
 * Flyway-managed database &rarr; REST API &rarr; Actuator. Uses H2; see
 * {@link PostgresIntegrationTest} for the same flow on real PostgreSQL.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ApplicationIntegrationTest {

    @MockitoBean
    TektonResourceClient tektonClient;

    @Autowired
    TektonCollectionService collectionService;

    @Autowired
    PipelineRunRepository pipelineRuns;

    @Autowired
    TaskRunRepository taskRuns;

    @Autowired
    MockMvc mvc;

    @BeforeEach
    void clean() {
        taskRuns.deleteAll();
        pipelineRuns.deleteAll();
    }

    @Test
    void collectedRunsAreServedByTheApi() throws Exception {
        String start = Instant.now().minus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS).toString();
        String end = Instant.parse(start).plusSeconds(120).toString();
        when(tektonClient.listPipelineRuns("ci")).thenReturn(List.of(
                pipelineRun("pr-ok", "build-ok", "ci", "build", "True", "Succeeded", null, start, end),
                pipelineRun("pr-ko", "build-ko", "ci", "build", "False", "Failed", "Tasks Completed: 1 (Failed: 1)", start, end)));
        when(tektonClient.listTaskRuns("ci")).thenReturn(List.of(
                taskRun("tr-1", "build-ko-test", "ci", "pr-ko", "build-ko", "build", "test", "False", "Failed",
                        "exit 1", start, end)));
        when(tektonClient.listPipelineRuns("staging")).thenReturn(List.of());
        when(tektonClient.listTaskRuns("staging")).thenReturn(List.of());

        collectionService.runCycle();
        collectionService.runCycle();

        assertThat(pipelineRuns.count()).isEqualTo(2);
        mvc.perform(get("/api/v1/dashboard/summary").param("range", "24h"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalExecutions").value(2))
                .andExpect(jsonPath("$.successRate").value(50.0))
                .andExpect(jsonPath("$.averageDurationMs").value(120000))
                .andExpect(jsonPath("$.failedTaskRuns").value(1));

        mvc.perform(get("/api/v1/pipelines").param("status", "FAILED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].pipelineRunName").value("build-ko"));

        Long failedId = pipelineRuns.findByPipelineRunUid("pr-ko").orElseThrow().getId();
        mvc.perform(get("/api/v1/pipelines/{id}/tasks", failedId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].taskName").value("test"));

        mvc.perform(get("/api/v1/pipelines").param("sort", "secret"))
                .andExpect(status().isBadRequest());

        mvc.perform(get("/api/v1/collector/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lastCycle.successful").value(true))
                .andExpect(jsonPath("$.lastCycle.discovered.PipelineRun").value(2));
    }

    @Test
    void healthReportsDegradedCollectorButStaysAvailable() throws Exception {
        when(tektonClient.listPipelineRuns("ci")).thenThrow(new KubernetesAccessException("connect timed out", null));
        when(tektonClient.listTaskRuns("ci")).thenReturn(List.of());
        when(tektonClient.listPipelineRuns("staging")).thenReturn(List.of());
        when(tektonClient.listTaskRuns("staging")).thenReturn(List.of());

        collectionService.runCycle();

        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components.db.status").value("UP"));
        mvc.perform(get("/actuator/info")).andExpect(status().isOk());
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/dashboard/summary']").exists());
    }

    @Test
    void unknownEndpointReturns404Json() throws Exception {
        mvc.perform(get("/api/v1/nope"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }
}
