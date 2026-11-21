package com.hackathon.agent;

import static com.hackathon.agent.support.TektonFixtures.pipelineRun;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hackathon.agent.collector.TektonCollectionService;
import com.hackathon.agent.kubernetes.TektonResourceClient;
import com.hackathon.agent.repository.PipelineRunRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Verifies the Flyway migrations, schema validation, idempotent upserts and metric queries on a real
 * PostgreSQL server. Skipped automatically when Docker is not available.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class PostgresIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    @MockitoBean
    TektonResourceClient tektonClient;

    @Autowired
    TektonCollectionService collectionService;

    @Autowired
    PipelineRunRepository pipelineRuns;

    @Autowired
    MockMvc mvc;

    @Test
    void collectsIdempotentlyAndServesMetricsOnPostgres() throws Exception {
        String start = Instant.now().minus(2, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS).toString();
        String end = Instant.parse(start).plusSeconds(60).toString();
        when(tektonClient.listPipelineRuns("ci")).thenReturn(List.of(
                pipelineRun("pg-1", "build-1", "ci", "build", "True", "Succeeded", null, start, end),
                pipelineRun("pg-2", "build-2", "ci", "build", "Unknown", "Running", null, start, null)));
        when(tektonClient.listTaskRuns("ci")).thenReturn(List.of());
        when(tektonClient.listPipelineRuns("staging")).thenReturn(List.of());
        when(tektonClient.listTaskRuns("staging")).thenReturn(List.of());

        collectionService.runCycle();
        collectionService.runCycle();

        assertThat(pipelineRuns.count()).isEqualTo(2);
        mvc.perform(get("/api/v1/dashboard/summary").param("range", "24h"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalExecutions").value(2))
                .andExpect(jsonPath("$.runningExecutions").value(1))
                .andExpect(jsonPath("$.p95DurationMs").value(60000));
        mvc.perform(get("/api/v1/dashboard/trends").param("range", "24h"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.points.length()").value(24));
    }
}
