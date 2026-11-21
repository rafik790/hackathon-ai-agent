package com.hackathon.agent.collector;

import static com.hackathon.agent.support.TektonFixtures.pipelineRun;
import static com.hackathon.agent.support.TektonFixtures.taskRun;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.hackathon.agent.config.MonitoringProperties;
import com.hackathon.agent.entity.PipelineRunEntity;
import com.hackathon.agent.entity.TaskRunEntity;
import com.hackathon.agent.enums.ExecutionStatus;
import com.hackathon.agent.kubernetes.TektonResourceClient;
import com.hackathon.agent.repository.PipelineRunRepository;
import com.hackathon.agent.repository.TaskRunRepository;
import com.hackathon.agent.service.PipelineRunUpsertService;
import com.hackathon.agent.service.TaskRunUpsertService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Runs full collection cycles (Kubernetes mocked, real database) to verify that repeated discovery
 * of the same resources never creates duplicates and that status changes update existing rows.
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({TektonCollectionService.class, PipelineRunCollector.class, TaskRunCollector.class,
        TektonResourceNormalizer.class, CollectionStatusTracker.class, PipelineRunUpsertService.class,
        TaskRunUpsertService.class, CollectorIdempotencyTest.Config.class})
class CollectorIdempotencyTest {

    private static final String NS = "ci";
    private static final String START = "2026-11-21T10:00:00Z";

    @TestConfiguration
    static class Config {
        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2026-11-21T12:00:00Z"), ZoneOffset.UTC);
        }

        @Bean
        MonitoringProperties monitoringProperties() {
            return new MonitoringProperties(true, 15000, 0, List.of(NS), null, 100, null);
        }
    }

    @MockitoBean
    private TektonResourceClient tektonClient;

    @Autowired
    private TektonCollectionService collectionService;

    @Autowired
    private PipelineRunRepository pipelineRuns;

    @Autowired
    private TaskRunRepository taskRuns;

    @Test
    void repeatedCyclesDoNotCreateDuplicates() {
        when(tektonClient.listPipelineRuns(NS)).thenReturn(List.of(
                pipelineRun("pr-1", "build-1", NS, "build", "True", "Succeeded", null, START, "2026-11-21T10:05:00Z"),
                pipelineRun("pr-2", "build-2", NS, "build", "Unknown", "Running", null, START, null)));
        when(tektonClient.listTaskRuns(NS)).thenReturn(List.of(
                taskRun("tr-1", "build-1-clone", NS, "pr-1", "build-1", "build", "clone", "True", "Succeeded", null,
                        START, "2026-11-21T10:01:00Z")));

        CollectionCycleReport first = collectionService.runCycle().orElseThrow();
        CollectionCycleReport second = collectionService.runCycle().orElseThrow();
        collectionService.runCycle();

        assertThat(first.created()).isEqualTo(3);
        assertThat(first.discoveredByKind()).containsEntry("PipelineRun", 2).containsEntry("TaskRun", 1);
        assertThat(second.created()).isZero();
        assertThat(second.updated()).isZero();
        assertThat(second.unchanged()).isEqualTo(3);
        assertThat(pipelineRuns.count()).isEqualTo(2);
        assertThat(taskRuns.count()).isEqualTo(1);
    }

    @Test
    void statusTransitionUpdatesExistingRecord() {
        when(tektonClient.listTaskRuns(NS)).thenReturn(List.of());
        when(tektonClient.listPipelineRuns(NS)).thenReturn(List.of(
                pipelineRun("pr-1", "build-1", NS, "build", "Unknown", "Running", null, START, null)));
        collectionService.runCycle();
        Long id = pipelineRuns.findByPipelineRunUid("pr-1").orElseThrow().getId();

        when(tektonClient.listPipelineRuns(NS)).thenReturn(List.of(
                pipelineRun("pr-1", "build-1", NS, "build", "False", "PipelineRunTimeout", "timed out", START,
                        "2026-11-21T11:00:00Z")));
        CollectionCycleReport report = collectionService.runCycle().orElseThrow();

        assertThat(report.updated()).isEqualTo(1);
        PipelineRunEntity updated = pipelineRuns.findByPipelineRunUid("pr-1").orElseThrow();
        assertThat(updated.getId()).isEqualTo(id);
        assertThat(updated.getStatus()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(updated.getFailureReason()).isEqualTo("PipelineRunTimeout");
        assertThat(updated.getDurationMs()).isEqualTo(3_600_000L);
        assertThat(pipelineRuns.count()).isEqualTo(1);
    }

    @Test
    void terminalStatusIsNotDowngradedByIncompleteData() {
        when(tektonClient.listTaskRuns(NS)).thenReturn(List.of());
        when(tektonClient.listPipelineRuns(NS)).thenReturn(List.of(
                pipelineRun("pr-1", "build-1", NS, "build", "True", "Succeeded", null, START, "2026-11-21T10:05:00Z")));
        collectionService.runCycle();

        when(tektonClient.listPipelineRuns(NS)).thenReturn(List.of(
                pipelineRun("pr-1", "build-1", NS, "build", null, null, null, null, null)));
        CollectionCycleReport report = collectionService.runCycle().orElseThrow();

        assertThat(report.unchanged()).isEqualTo(1);
        PipelineRunEntity entity = pipelineRuns.findByPipelineRunUid("pr-1").orElseThrow();
        assertThat(entity.getStatus()).isEqualTo(ExecutionStatus.SUCCEEDED);
        assertThat(entity.getDurationMs()).isEqualTo(300_000L);
    }

    @Test
    void taskRunStatusChangesAreTracked() {
        when(tektonClient.listPipelineRuns(NS)).thenReturn(List.of());
        when(tektonClient.listTaskRuns(NS)).thenReturn(List.of(
                taskRun("tr-1", "b-1-test", NS, "pr-1", "b-1", "build", "test", "Unknown", "Running", null, START, null)));
        collectionService.runCycle();

        when(tektonClient.listTaskRuns(NS)).thenReturn(List.of(
                taskRun("tr-1", "b-1-test", NS, "pr-1", "b-1", "build", "test", "False", "Failed",
                        "\"step-test\" exited with code 1", START, "2026-11-21T10:00:42Z")));
        collectionService.runCycle();

        TaskRunEntity entity = taskRuns.findByTaskRunUid("tr-1").orElseThrow();
        assertThat(entity.getStatus()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(entity.getFailureMessage()).contains("exited with code 1");
        assertThat(entity.getDurationMs()).isEqualTo(42_000L);
        assertThat(taskRuns.count()).isEqualTo(1);
    }

    @Test
    void duplicateObjectsWithinOneListAreMerged() {
        when(tektonClient.listTaskRuns(NS)).thenReturn(List.of());
        when(tektonClient.listPipelineRuns(NS)).thenReturn(List.of(
                pipelineRun("pr-1", "build-1", NS, "build", "Unknown", "Running", null, START, null),
                pipelineRun("pr-1", "build-1", NS, "build", "Unknown", "Running", null, START, null)));

        CollectionCycleReport report = collectionService.runCycle().orElseThrow();

        assertThat(report.created()).isEqualTo(1);
        assertThat(pipelineRuns.count()).isEqualTo(1);
    }
}
