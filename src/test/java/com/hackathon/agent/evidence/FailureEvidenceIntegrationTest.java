package com.hackathon.agent.evidence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hackathon.agent.entity.TaskRunEntity;
import com.hackathon.agent.enums.ExecutionStatus;
import com.hackathon.agent.evidence.client.EvidenceApiException;
import com.hackathon.agent.evidence.client.EvidenceApiException.Category;
import com.hackathon.agent.evidence.client.KubernetesEvidenceClient;
import com.hackathon.agent.evidence.entity.ContainerEvidenceEntity;
import com.hackathon.agent.evidence.entity.ContainerLogEntity;
import com.hackathon.agent.evidence.entity.FailureEvidenceEntity;
import com.hackathon.agent.evidence.model.ContainerType;
import com.hackathon.agent.evidence.model.EvidenceCycleReport;
import com.hackathon.agent.evidence.model.EvidenceStatus;
import com.hackathon.agent.evidence.model.ExitCodeSource;
import com.hackathon.agent.evidence.model.LogStatus;
import com.hackathon.agent.evidence.repository.ContainerEvidenceRepository;
import com.hackathon.agent.evidence.repository.ContainerLogRepository;
import com.hackathon.agent.evidence.repository.FailureEvidenceRepository;
import com.hackathon.agent.evidence.service.FailureEvidenceService;
import com.hackathon.agent.kubernetes.TektonResourceClient;
import com.hackathon.agent.repository.TaskRunRepository;
import com.hackathon.agent.support.EvidenceFixtures;
import io.fabric8.kubernetes.api.model.Pod;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Evidence cycles through the full application context with the V2 schema; only the Kubernetes API is mocked.
 * Uses H2 unless EVIDENCE_TEST_DB_URL points to another database.
 */
@SpringBootTest(properties = {
        "evidence.enabled=true",
        "spring.datasource.url=${EVIDENCE_TEST_DB_URL:jdbc:h2:mem:evidence;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;"
                + "DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1}",
        "spring.datasource.username=${EVIDENCE_TEST_DB_USER:sa}"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class FailureEvidenceIntegrationTest {

    private static final Instant START = Instant.parse("2026-11-21T12:00:00Z");

    @MockitoBean
    KubernetesEvidenceClient client;

    @MockitoBean
    TektonResourceClient phase1Client;

    @MockitoBean
    Clock clock;

    @Autowired
    FailureEvidenceService service;

    @Autowired
    TaskRunRepository taskRuns;

    @Autowired
    FailureEvidenceRepository evidence;

    @Autowired
    ContainerEvidenceRepository containers;

    @Autowired
    ContainerLogRepository logs;

    @Autowired
    MockMvc mvc;

    private Instant now = START;

    @BeforeEach
    void setUp() {
        when(clock.instant()).thenAnswer(call -> now);
        taskRuns.deleteAll();
    }

    @AfterEach
    void cleanUp() {
        taskRuns.deleteAll();
    }

    @Test
    void collectsPodContainersAndLogs() {
        TaskRunEntity taskRun = failedTaskRun("build", START.minusSeconds(60));
        clusterHasTaskRunAndPod("build");
        logIs("step-test", "compiling\npassword=hunter2\nBUILD FAILED");

        EvidenceCycleReport report = cycle();

        assertThat(report.taskRunsExamined()).isEqualTo(1);
        assertThat(report.evidenceCreated()).isEqualTo(1);
        assertThat(report.logsFetched()).isEqualTo(1);
        assertThat(report.failedApiCalls()).isZero();
        FailureEvidenceEntity row = onlyEvidence(taskRun);
        assertThat(row.getAttempt()).isZero();
        assertThat(row.getStatus()).isEqualTo(EvidenceStatus.COLLECTED);
        assertThat(row.getPodName()).isEqualTo("build-pod");
        assertThat(row.getPodUid()).isEqualTo("pod-build-pod");
        assertThat(row.getPodPhase()).isEqualTo("Failed");
        assertThat(row.getPodReason()).isEqualTo("ExampleReason");
        assertThat(row.getPodMessage()).isEqualTo("pod message");
        assertThat(row.getFetchAttempts()).isEqualTo(1);
        assertThat(row.getCollectedAt()).isEqualTo(START);
        assertThat(row.getNextAttemptAt()).isNull();
        assertThat(row.getLastError()).isNull();

        List<ContainerEvidenceEntity> rows = containers.findByEvidenceId(row.getId());
        assertThat(rows).extracting(ContainerEvidenceEntity::getContainerName,
                        ContainerEvidenceEntity::getContainerType, ContainerEvidenceEntity::isPrimaryFailure)
                .containsExactlyInAnyOrder(tuple("prepare", ContainerType.INIT, false),
                        tuple("step-test", ContainerType.STEP, true),
                        tuple("step-publish", ContainerType.STEP, false),
                        tuple("sidecar-db", ContainerType.SIDECAR, false));
        ContainerEvidenceEntity primary = container(row, "step-test");
        assertThat(primary.getExitCode()).isEqualTo(2);
        assertThat(primary.getExitCodeSource()).isEqualTo(ExitCodeSource.POD);
        assertThat(primary.getMessage()).isEqualTo("password=[REDACTED]");

        ContainerLogEntity log = onlyLog(primary);
        assertThat(log.getStatus()).isEqualTo(LogStatus.COLLECTED);
        assertThat(log.isPrevious()).isFalse();
        assertThat(log.getContent()).isEqualTo("compiling\npassword=[REDACTED]\nBUILD FAILED");
        assertThat(log.getStoredBytes()).isEqualTo(log.getContent().length());
        assertThat(log.getSha256()).hasSize(64);
        assertThat(log.getRedactions()).isEqualTo(1);
        assertThat(log.getFetchedAt()).isEqualTo(START);
        assertThat(log.getExpiresAt()).isEqualTo(START.plus(Duration.ofDays(14)));
        verify(client, times(1)).openLog(any(), any(), any(), anyBoolean(), anyInt());
    }

    @Test
    void finalEvidenceIsNeverFetchedAgain() {
        TaskRunEntity taskRun = failedTaskRun("build", START.minusSeconds(60));
        clusterHasTaskRunAndPod("build");
        logIs("step-test", "BUILD FAILED");
        cycle();
        clearInvocations(client);

        now = START.plus(Duration.ofHours(1));
        EvidenceCycleReport report = cycle();

        assertThat(report.taskRunsExamined()).isZero();
        verifyNoInteractions(client);
        assertThat(evidence.findByTaskRunIdOrderByAttempt(taskRun.getId())).hasSize(1);
    }

    @Test
    void databaseRejectsDuplicateEvidenceOfAnAttempt() {
        TaskRunEntity taskRun = failedTaskRun("build", START.minusSeconds(60));
        clusterHasTaskRunAndPod("build");
        logIs("step-test", "x");
        cycle();
        FailureEvidenceEntity duplicate = new FailureEvidenceEntity();
        duplicate.setTaskRunId(taskRun.getId());
        duplicate.setStatus(EvidenceStatus.PENDING);
        duplicate.setCreatedAt(START);
        duplicate.setUpdatedAt(START);

        assertThatThrownBy(() -> evidence.saveAndFlush(duplicate)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void noTransactionIsOpenDuringKubernetesCalls() {
        failedTaskRun("build", START.minusSeconds(60));
        when(client.getTaskRun("ci", "build")).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return Optional.of(EvidenceFixtures.failedTaskRun("build", "uid-build"));
        });
        when(client.getPod("ci", "build-pod")).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return Optional.of(EvidenceFixtures.failedPod("build-pod", "uid-build"));
        });
        logIs("step-test", "x");

        assertThat(cycle().taskRunErrors()).isZero();
    }

    @Test
    void everyAttemptOfARetriedTaskRunGetsItsOwnEvidence() {
        TaskRunEntity taskRun = failedTaskRun("build", START.minusSeconds(60));
        when(client.getTaskRun("ci", "build"))
                .thenReturn(Optional.of(EvidenceFixtures.retriedTaskRun("build", "uid-build")));
        when(client.getPod("ci", "build-pod")).thenReturn(Optional.empty());
        when(client.getPod("ci", "build-pod-retry1"))
                .thenReturn(Optional.of(EvidenceFixtures.failedPod("build-pod-retry1", "uid-build")));
        logIs("step-test", "second attempt failed");

        cycle();

        List<FailureEvidenceEntity> rows = evidence.findByTaskRunIdOrderByAttempt(taskRun.getId());
        assertThat(rows).extracting(FailureEvidenceEntity::getAttempt, FailureEvidenceEntity::getStatus)
                .containsExactly(tuple(0, EvidenceStatus.POD_DELETED), tuple(1, EvidenceStatus.COLLECTED));
        ContainerEvidenceEntity timedOut = container(rows.get(0), "step-test");
        assertThat(timedOut.getExitCodeSource()).isEqualTo(ExitCodeSource.SYNTHETIC);
        assertThat(timedOut.isPrimaryFailure()).isTrue();
    }

    @Test
    void deletedPodKeepsWhatTektonReported() {
        TaskRunEntity taskRun = failedTaskRun("build", START.minusSeconds(60));
        clusterHasTaskRunAndPod("build");
        when(client.getPod("ci", "build-pod")).thenReturn(Optional.empty());

        EvidenceCycleReport report = cycle();

        assertThat(report.failedApiCalls()).isZero();
        FailureEvidenceEntity row = onlyEvidence(taskRun);
        assertThat(row.getStatus()).isEqualTo(EvidenceStatus.POD_DELETED);
        assertThat(row.getPodUid()).isNull();
        ContainerEvidenceEntity step = container(row, "step-test");
        assertThat(step.getExitCode()).isEqualTo(2);
        assertThat(step.getExitCodeSource()).isEqualTo(ExitCodeSource.TASKRUN);
        assertThat(step.isPrimaryFailure()).isTrue();
        assertThat(logs.findByContainerEvidenceId(step.getId())).isEmpty();
    }

    @Test
    void podOfAnotherTaskRunIsNotUsed() {
        TaskRunEntity taskRun = failedTaskRun("build", START.minusSeconds(60));
        clusterHasTaskRunAndPod("build");
        when(client.getPod("ci", "build-pod"))
                .thenReturn(Optional.of(EvidenceFixtures.failedPod("build-pod", "uid-of-another")));

        cycle();

        assertThat(onlyEvidence(taskRun).getStatus()).isEqualTo(EvidenceStatus.POD_DELETED);
        assertThat(onlyEvidence(taskRun).getLastError()).contains("not owned");
        verify(client, never()).openLog(any(), any(), any(), anyBoolean(), anyInt());
    }

    @Test
    void taskRunWithoutPodIsFinalImmediately() {
        TaskRunEntity taskRun = failedTaskRun("build", START.minusSeconds(60));
        when(client.getTaskRun("ci", "build"))
                .thenReturn(Optional.of(EvidenceFixtures.taskRunWithoutPod("build", "uid-build")));

        cycle();

        assertThat(onlyEvidence(taskRun).getStatus()).isEqualTo(EvidenceStatus.NO_POD);
        verify(client, never()).getPod(any(), any());
    }

    @Test
    void deletedOrRecreatedTaskRunIsFinalImmediately() {
        TaskRunEntity deleted = failedTaskRun("deleted", START.minusSeconds(60));
        TaskRunEntity recreated = failedTaskRun("recreated", START.minusSeconds(60));
        when(client.getTaskRun("ci", "deleted")).thenReturn(Optional.empty());
        when(client.getTaskRun("ci", "recreated"))
                .thenReturn(Optional.of(EvidenceFixtures.failedTaskRun("recreated", "new-uid")));

        cycle();

        assertThat(onlyEvidence(deleted).getStatus()).isEqualTo(EvidenceStatus.RESOURCE_DELETED);
        assertThat(onlyEvidence(recreated).getStatus()).isEqualTo(EvidenceStatus.RESOURCE_DELETED);
        verify(client, never()).getPod(any(), any());
    }

    @Test
    void transientErrorsAreRetriedWithBackoffAndThenGivenUp() {
        TaskRunEntity taskRun = failedTaskRun("build", START.minusSeconds(60));
        when(client.getTaskRun("ci", "build"))
                .thenThrow(new EvidenceApiException(Category.TRANSIENT, "get taskrun ci/build failed: HTTP 503", null));

        cycle();
        FailureEvidenceEntity row = onlyEvidence(taskRun);
        assertThat(row.getStatus()).isEqualTo(EvidenceStatus.PENDING);
        assertThat(row.getNextAttemptAt()).isEqualTo(START.plusSeconds(60));
        assertThat(row.getLastError()).contains("HTTP 503");
        assertThat(row.getCollectedAt()).isNull();

        now = START.plusSeconds(59);
        assertThat(cycle().taskRunsExamined()).isZero();

        for (int attempt = 2; attempt <= 5; attempt++) {
            now = now.plus(Duration.ofHours(2));
            assertThat(cycle().evidenceUpdated()).isEqualTo(1);
            assertThat(onlyEvidence(taskRun).getFetchAttempts()).isEqualTo(attempt);
        }
        assertThat(onlyEvidence(taskRun).getStatus()).isEqualTo(EvidenceStatus.FAILED);

        now = now.plus(Duration.ofHours(2));
        assertThat(cycle().taskRunsExamined()).isZero();
        verify(client, times(5)).getTaskRun("ci", "build");
    }

    @Test
    void permanentErrorIsNotRetried() {
        TaskRunEntity taskRun = failedTaskRun("build", START.minusSeconds(60));
        clusterHasTaskRunAndPod("build");
        when(client.getPod("ci", "build-pod"))
                .thenThrow(new EvidenceApiException(Category.PERMANENT, "get pod ci/build-pod failed: HTTP 400", null));

        cycle();
        now = START.plus(Duration.ofHours(2));
        cycle();

        assertThat(onlyEvidence(taskRun).getStatus()).isEqualTo(EvidenceStatus.FAILED);
        verify(client, times(1)).getPod("ci", "build-pod");
    }

    @Test
    void missingPermissionIsRetriedOnlyFiveTimes() {
        TaskRunEntity taskRun = failedTaskRun("build", START.minusSeconds(60));
        clusterHasTaskRunAndPod("build");
        when(client.getPod("ci", "build-pod"))
                .thenThrow(new EvidenceApiException(Category.FORBIDDEN, "get pod ci/build-pod failed: HTTP 403", null));

        assertThat(cycle().forbiddenApiCalls()).isEqualTo(1);
        assertThat(onlyEvidence(taskRun).getStatus()).isEqualTo(EvidenceStatus.PENDING);
        for (int i = 0; i < 8; i++) {
            now = now.plus(Duration.ofHours(2));
            cycle();
        }

        assertThat(onlyEvidence(taskRun).getStatus()).isEqualTo(EvidenceStatus.FORBIDDEN);
        verify(client, times(5)).getPod("ci", "build-pod");
    }

    @Test
    void onlyMissingLogsAreFetchedOnRetry() {
        TaskRunEntity taskRun = failedTaskRun("build", START.minusSeconds(60));
        clusterHasTaskRunAndPod("build");
        Pod pod = EvidenceFixtures.failedPod("build-pod", "uid-build");
        pod.getStatus().getContainerStatuses().stream().filter(c -> c.getName().equals("step-test"))
                .forEach(c -> c.setRestartCount(1));
        when(client.getPod("ci", "build-pod")).thenReturn(Optional.of(pod));
        logIs("step-test", "current");
        when(client.openLog(any(), any(), eq("step-test"), eq(true), anyInt()))
                .thenThrow(new EvidenceApiException(Category.FORBIDDEN, "get log failed: HTTP 403", null))
                .thenReturn(stream("previous"));

        cycle();
        assertThat(onlyEvidence(taskRun).getStatus()).isEqualTo(EvidenceStatus.PENDING);
        assertThat(onlyEvidence(taskRun).getPodUid()).isNotNull();

        now = START.plusSeconds(61);
        EvidenceCycleReport retry = cycle();

        assertThat(retry.logsFetched()).isEqualTo(1);
        assertThat(retry.logsSkipped()).isEqualTo(1);
        FailureEvidenceEntity row = onlyEvidence(taskRun);
        assertThat(row.getStatus()).isEqualTo(EvidenceStatus.COLLECTED);
        assertThat(logs.findByContainerEvidenceId(container(row, "step-test").getId()))
                .extracting(ContainerLogEntity::isPrevious, ContainerLogEntity::getContent)
                .containsExactlyInAnyOrder(tuple(false, "current"), tuple(true, "previous"));
        verify(client, times(1)).openLog(any(), any(), eq("step-test"), eq(false), anyInt());
    }

    @Test
    void podDeletedBeforeTheRetryKeepsTheEvidenceAlreadyCollected() {
        TaskRunEntity taskRun = failedTaskRun("build", START.minusSeconds(60));
        clusterHasTaskRunAndPod("build");
        when(client.openLog(any(), any(), any(), anyBoolean(), anyInt()))
                .thenThrow(new EvidenceApiException(Category.TRANSIENT, "get log failed: HTTP 500", null));
        cycle();
        when(client.getPod("ci", "build-pod")).thenReturn(Optional.empty());

        now = START.plusSeconds(61);
        cycle();

        FailureEvidenceEntity row = onlyEvidence(taskRun);
        assertThat(row.getStatus()).isEqualTo(EvidenceStatus.PARTIAL);
        assertThat(row.getPodPhase()).isEqualTo("Failed");
        assertThat(container(row, "step-test").getExitCodeSource()).isEqualTo(ExitCodeSource.POD);
        assertThat(onlyLog(container(row, "step-test")).getStatus()).isEqualTo(LogStatus.ERROR);
    }

    @Test
    void unavailableLogMakesTheEvidencePartialWithoutRetry() {
        TaskRunEntity taskRun = failedTaskRun("build", START.minusSeconds(60));
        clusterHasTaskRunAndPod("build");
        when(client.openLog(any(), any(), any(), anyBoolean(), anyInt()))
                .thenThrow(new EvidenceApiException(Category.PERMANENT, "get log failed: HTTP 400", null));

        cycle();

        assertThat(onlyEvidence(taskRun).getStatus()).isEqualTo(EvidenceStatus.PARTIAL);
        assertThat(onlyEvidence(taskRun).getLastError()).isEqualTo("Incomplete logs: step-test=NOT_AVAILABLE");
    }

    @Test
    void logLimitsApplyToTheWholeTaskRun() {
        TaskRunEntity taskRun = failedTaskRun("build", START.minusSeconds(60));
        clusterHasTaskRunAndPod("build");
        when(client.getPod("ci", "build-pod")).thenReturn(Optional.of(podWithFailedSteps(5, 1)));
        when(client.openLog(any(), any(), any(), anyBoolean(), anyInt()))
                .thenAnswer(call -> stream("line\n".repeat(40_000)));

        cycle();

        FailureEvidenceEntity row = onlyEvidence(taskRun);
        List<ContainerLogEntity> stored = containers.findByEvidenceId(row.getId()).stream()
                .flatMap(c -> logs.findByContainerEvidenceId(c.getId()).stream()).toList();
        assertThat(stored.stream().map(ContainerLogEntity::getContainerEvidenceId).distinct()).hasSize(3);
        assertThat(stored.stream().filter(l -> l.getStatus() == LogStatus.COLLECTED)
                .mapToInt(ContainerLogEntity::getStoredBytes)).allMatch(bytes -> bytes <= 65536);
        assertThat(stored.stream().filter(l -> l.getStatus() == LogStatus.COLLECTED)
                .mapToInt(ContainerLogEntity::getStoredBytes).sum()).isLessThanOrEqualTo(262144).isGreaterThan(250_000);
        assertThat(stored).extracting(ContainerLogEntity::getStatus).contains(LogStatus.SKIPPED_LIMIT);
        assertThat(row.getStatus()).isEqualTo(EvidenceStatus.PARTIAL);
    }

    @Test
    void storesLogsThatPostgresWouldRejectUnsanitized() {
        TaskRunEntity taskRun = failedTaskRun("build", START.minusSeconds(60));
        clusterHasTaskRunAndPod("build");
        when(client.openLog(any(), any(), any(), anyBoolean(), anyInt()))
                .thenReturn(new ByteArrayInputStream(new byte[] {'a', 0, (byte) 0xFF, 'b'}));

        assertThat(cycle().taskRunErrors()).isZero();

        assertThat(onlyLog(container(onlyEvidence(taskRun), "step-test")).getContent()).isEqualTo("a\uFFFDb");
    }

    @Test
    void onlyRecentFailuresOfMonitoredNamespacesAreExamined() {
        failedTaskRun("too-old", START.minus(Duration.ofHours(25)));
        TaskRunEntity otherNamespace = failedTaskRun("other", START.minusSeconds(60));
        otherNamespace.setNamespace("not-monitored");
        taskRuns.save(otherNamespace);
        TaskRunEntity succeeded = failedTaskRun("succeeded", START.minusSeconds(60));
        succeeded.setStatus(ExecutionStatus.SUCCEEDED);
        taskRuns.save(succeeded);

        assertThat(cycle().taskRunsExamined()).isZero();
        verifyNoInteractions(client);
    }

    @Test
    void processesAtMostTenTaskRunsPerCycleNewestFirst() {
        for (int i = 0; i < 12; i++) {
            failedTaskRun("run-" + i, START.minusSeconds(60 + i));
        }
        when(client.getTaskRun(any(), any())).thenReturn(Optional.empty());

        assertThat(cycle().taskRunsExamined()).isEqualTo(10);
        verify(client, never()).getTaskRun("ci", "run-11");
        assertThat(cycle().taskRunsExamined()).isEqualTo(2);
        assertThat(cycle().taskRunsExamined()).isZero();
    }

    @Test
    void oneFailingTaskRunDoesNotStopTheCycle() {
        TaskRunEntity broken = failedTaskRun("broken", START.minusSeconds(30));
        TaskRunEntity build = failedTaskRun("build", START.minusSeconds(60));
        when(client.getTaskRun("ci", "broken")).thenThrow(new IllegalStateException("unexpected"));
        clusterHasTaskRunAndPod("build");
        logIs("step-test", "x");

        EvidenceCycleReport report = cycle();

        assertThat(report.taskRunErrors()).isEqualTo(1);
        assertThat(onlyEvidence(build).getStatus()).isEqualTo(EvidenceStatus.COLLECTED);
        assertThat(onlyEvidence(broken).getStatus()).isEqualTo(EvidenceStatus.PENDING);
        assertThat(onlyEvidence(broken).getFetchAttempts()).isEqualTo(1);
    }

    @Test
    void evidenceNeverChangesPhase1DataOrOverallHealth() throws Exception {
        TaskRunEntity before = failedTaskRun("build", START.minusSeconds(60));
        when(client.getTaskRun("ci", "build"))
                .thenThrow(new EvidenceApiException(Category.FORBIDDEN, "get taskrun ci/build failed: HTTP 403", null));

        cycle();

        TaskRunEntity after = taskRuns.findById(before.getId()).orElseThrow();
        assertThat(after).usingRecursiveComparison().isEqualTo(before);
        mvc.perform(get("/actuator/health")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components.tektonCollector.status").value("UP"))
                .andExpect(jsonPath("$.components.evidenceCollector.status").value("UP"));
    }

    private EvidenceCycleReport cycle() {
        return service.runCycle().orElseThrow();
    }

    private TaskRunEntity failedTaskRun(String name, Instant completion) {
        TaskRunEntity e = new TaskRunEntity();
        e.setTaskRunUid("uid-" + name);
        e.setTaskRunName(name);
        e.setNamespace("ci");
        e.setTaskName("test");
        e.setStatus(ExecutionStatus.FAILED);
        e.setStartTime(completion.minusSeconds(30));
        e.setCompletionTime(completion);
        e.setDurationMs(30_000L);
        e.setCreatedAt(completion);
        e.setUpdatedAt(completion);
        return taskRuns.save(e);
    }

    private void clusterHasTaskRunAndPod(String name) {
        when(client.getTaskRun("ci", name))
                .thenReturn(Optional.of(EvidenceFixtures.failedTaskRun(name, "uid-" + name)));
        when(client.getPod("ci", name + "-pod"))
                .thenReturn(Optional.of(EvidenceFixtures.failedPod(name + "-pod", "uid-" + name)));
    }

    private void logIs(String container, String content) {
        when(client.openLog(any(), any(), eq(container), eq(false), anyInt())).thenAnswer(call -> stream(content));
    }

    private static ByteArrayInputStream stream(String content) {
        return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
    }

    private static Pod podWithFailedSteps(int steps, int restarts) {
        StringBuilder statuses = new StringBuilder();
        for (int i = 0; i < steps; i++) {
            statuses.append(i == 0 ? "" : ",").append("""
                    {"name": "step-s%d", "restartCount": %d, "state": {"terminated": {"exitCode": 1, "reason": "Error"}}}"""
                    .formatted(i, restarts));
        }
        return EvidenceFixtures.pod("""
                {"metadata": {"name": "build-pod", "uid": "p", "ownerReferences": [{"kind": "TaskRun", "uid": "uid-build",
                 "name": "build", "apiVersion": "tekton.dev/v1"}]}, "spec": {}, "status": {"phase": "Failed",
                 "containerStatuses": [%s]}}""".formatted(statuses));
    }

    private FailureEvidenceEntity onlyEvidence(TaskRunEntity taskRun) {
        List<FailureEvidenceEntity> rows = evidence.findByTaskRunIdOrderByAttempt(taskRun.getId());
        assertThat(rows).hasSize(1);
        return rows.getFirst();
    }

    private ContainerEvidenceEntity container(FailureEvidenceEntity row, String name) {
        return containers.findByEvidenceId(row.getId()).stream().filter(c -> c.getContainerName().equals(name))
                .findFirst().orElseThrow();
    }

    private ContainerLogEntity onlyLog(ContainerEvidenceEntity container) {
        List<ContainerLogEntity> rows = logs.findByContainerEvidenceId(container.getId());
        assertThat(rows).hasSize(1);
        return rows.getFirst();
    }
}
