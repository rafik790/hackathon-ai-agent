package com.hackathon.agent.investigation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hackathon.agent.ai.llm.LlmProvider;
import com.hackathon.agent.ai.llm.LlmProviderException;
import com.hackathon.agent.ai.llm.LlmRequest;
import com.hackathon.agent.ai.llm.LlmResponse;
import com.hackathon.agent.ai.llm.LlmUsage;
import com.hackathon.agent.entity.PipelineRunEntity;
import com.hackathon.agent.entity.TaskRunEntity;
import com.hackathon.agent.enums.ExecutionStatus;
import com.hackathon.agent.evidence.entity.ContainerEvidenceEntity;
import com.hackathon.agent.evidence.entity.ContainerLogEntity;
import com.hackathon.agent.evidence.entity.FailureEvidenceEntity;
import com.hackathon.agent.evidence.model.ContainerRunState;
import com.hackathon.agent.evidence.model.ContainerType;
import com.hackathon.agent.evidence.model.EvidenceStatus;
import com.hackathon.agent.evidence.model.ExitCodeSource;
import com.hackathon.agent.evidence.model.LogStatus;
import com.hackathon.agent.evidence.repository.ContainerEvidenceRepository;
import com.hackathon.agent.evidence.repository.ContainerLogRepository;
import com.hackathon.agent.evidence.repository.FailureEvidenceRepository;
import com.hackathon.agent.investigation.entity.AgentInvestigationEntity;
import com.hackathon.agent.investigation.entity.AgentInvestigationStepEntity;
import com.hackathon.agent.investigation.model.InvestigationModel.InvestigationStatus;
import com.hackathon.agent.investigation.model.InvestigationModel.StepAction;
import com.hackathon.agent.investigation.model.InvestigationModel.StepStatus;
import com.hackathon.agent.investigation.repository.AgentInvestigationRepository;
import com.hackathon.agent.investigation.repository.AgentInvestigationStepRepository;
import com.hackathon.agent.investigation.service.AgentInvestigationStore;
import com.hackathon.agent.investigation.service.AgentInvestigationWorker;
import com.hackathon.agent.investigation.tool.AgentKubernetesReader;
import com.hackathon.agent.investigation.tool.AgentToolException;
import com.hackathon.agent.kubernetes.TektonResourceClient;
import com.hackathon.agent.repository.PipelineRunRepository;
import com.hackathon.agent.repository.TaskRunRepository;
import com.hackathon.agent.support.EvidenceFixtures;
import com.hackathon.agent.support.TestEntities;
import io.fabric8.kubernetes.api.model.ServiceAccountBuilder;
import io.fabric8.kubernetes.api.model.rbac.PolicyRuleBuilder;
import io.fabric8.kubernetes.api.model.rbac.RoleBinding;
import io.fabric8.kubernetes.api.model.rbac.RoleBindingBuilder;
import io.fabric8.kubernetes.api.model.rbac.RoleBuilder;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Investigations through the full application context and the V5 schema. The LLM and Kubernetes are mocked; no
 * cluster, provider or network is needed. Runs on H2 unless AGENT_TEST_DB_URL points to another database.
 */
@SpringBootTest(properties = {
        "ai.enabled=true", "ai.provider=mock", "ai.analysis.initial-delay-ms=86400000", "ai.analysis.daily-limit=50",
        "agent.investigation.enabled=true", "agent.investigation.max-steps=6", "agent.investigation.max-tool-calls=4",
        "agent.investigation.timeout-seconds=300",
        // The worker is started but must not interfere; tests process investigations themselves.
        "agent.investigation.poll-interval-ms=86400000",
        "spring.datasource.url=${AGENT_TEST_DB_URL:jdbc:h2:mem:investigation;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;"
                + "DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1}",
        "spring.datasource.username=${AGENT_TEST_DB_USER:sa}"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AgentInvestigationIntegrationTest {

    static final Instant START = Instant.parse("2026-11-27T10:00:00Z");
    static final String NS = "ci";

    @MockitoBean
    LlmProvider provider;
    @MockitoBean
    AgentKubernetesReader reader;
    @MockitoBean
    TektonResourceClient phase1Client;
    @MockitoBean
    Clock clock;

    @Autowired
    MockMvc mvc;
    @Autowired
    AgentInvestigationWorker worker;
    @Autowired
    AgentInvestigationStore store;
    @Autowired
    AgentInvestigationRepository investigations;
    @Autowired
    AgentInvestigationStepRepository steps;
    @Autowired
    PipelineRunRepository pipelineRuns;
    @Autowired
    TaskRunRepository taskRuns;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    FailureEvidenceRepository evidenceRepository;
    @Autowired
    ContainerEvidenceRepository containerRepository;
    @Autowired
    ContainerLogRepository logRepository;

    Instant now = START;
    final Deque<Object> answers = new ArrayDeque<>();
    final List<LlmRequest> requests = new ArrayList<>();
    PipelineRunEntity run;
    TaskRunEntity failedTask;

    @BeforeEach
    void setUp() {
        cleanUp();
        when(clock.instant()).thenAnswer(call -> now);
        when(provider.name()).thenReturn("test-provider");
        when(provider.model()).thenReturn("test-model");
        when(provider.consumesQuota()).thenReturn(true);
        when(provider.generate(any())).thenAnswer(call -> {
            requests.add(call.getArgument(0));
            Object next = answers.isEmpty() ? finalReport("Nothing left to ask", 0.2) : answers.poll();
            if (next instanceof RuntimeException e) {
                throw e;
            }
            if (next instanceof Runnable sideEffect) {
                sideEffect.run();
                next = answers.poll();
            }
            return new LlmResponse((String) next, "test-provider", "test-model", new LlmUsage(100, 20));
        });
        run = pipelineRuns.save(TestEntities.pipelineRun("p1", NS, "build", ExecutionStatus.FAILED, START.minusSeconds(600), 120_000L));
        failedTask = taskRuns.save(failed(TestEntities.taskRun("t1", run, "deploy", ExecutionStatus.FAILED, START.minusSeconds(500), 30_000L)));
        taskRuns.save(TestEntities.taskRun("t2", run, "compile", ExecutionStatus.SUCCEEDED, START.minusSeconds(590), 60_000L));
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("delete from agent_investigation");
        jdbc.update("delete from ai_invocation");
        taskRuns.deleteAll();
        pipelineRuns.deleteAll();
    }

    // ---------------------------------------------------------------- starting

    @Test
    void startQueuesAnInvestigationAndReturnsItsId() throws Exception {
        start("{\"taskRunName\": \"%s\", \"namespace\": \"ci\"}".formatted(failedTask.getTaskRunName()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.targetType").value("TASK_RUN"))
                .andExpect(jsonPath("$.taskRunId").value(failedTask.getId()));
        assertThat(investigations.count()).isEqualTo(1);
        verify(provider, never()).generate(any());
    }

    @Test
    void startOfAPipelineRunAndRepeatedStartReturnTheActiveInvestigation() throws Exception {
        String body = "{\"pipelineRunName\": \"%s\", \"namespace\": \"ci\"}".formatted(run.getPipelineRunName());
        long id = idOf(start(body).andExpect(status().isAccepted()).andExpect(jsonPath("$.targetType").value("PIPELINE_RUN")));

        start(body).andExpect(status().isAccepted()).andExpect(jsonPath("$.id").value(id));
        assertThat(investigations.count()).isEqualTo(1);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "{\"namespace\": \"ci\"}                                                     | 400 | Exactly one of taskRunName and pipelineRunName",
            "{\"taskRunName\": \"a\", \"pipelineRunName\": \"b\", \"namespace\": \"ci\"} | 400 | Exactly one of taskRunName and pipelineRunName",
            "{\"taskRunName\": \"Bad_Name\", \"namespace\": \"ci\"}                      | 400 | taskRunName must be a Kubernetes object name",
            "{\"taskRunName\": \"x\", \"namespace\": \"kube-system\"}                    | 400 | namespace kube-system is not monitored",
            "{\"taskRunName\": \"x\"}                                                    | 400 | namespace is required",
            "{\"taskRunName\": \"unknown-run\", \"namespace\": \"ci\"}                   | 404 | not found",
            "{\"pipelineRunName\": \"unknown-run\", \"namespace\": \"ci\"}               | 404 | not found"})
    void invalidReferencesAreRejected(String body, int status, String message) throws Exception {
        ResultActions result = start(body).andExpect(status().is(status));
        if (message != null) {
            result.andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString(message.trim())));
        }
        assertThat(investigations.count()).isZero();
    }

    @Test
    void onlyFailedRunsCanBeInvestigated() throws Exception {
        start("{\"taskRunName\": \"%s\", \"namespace\": \"ci\"}".formatted(run.getPipelineRunName() + "-compile"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("only failed runs")));
    }

    // ---------------------------------------------------------------- the agent loop

    @Test
    void agentChoosesToolsFromEarlierResultsAndReports() throws Exception {
        String sa = "tekton-restricted-builder";
        when(reader.taskRun(NS, failedTask.getTaskRunName())).thenReturn(Optional.of(EvidenceFixtures.resource("""
                {"apiVersion": "tekton.dev/v1", "kind": "TaskRun",
                 "metadata": {"name": "%s", "namespace": "ci", "uid": "t1", "labels": {"tekton.dev/pipelineRun": "%s"}},
                 "spec": {"serviceAccountName": "%s"},
                 "status": {"podName": "deploy-pod", "conditions": [{"type": "Succeeded", "status": "False", "reason": "Failed",
                  "message": "deployments.apps is forbidden: User cannot get resource deployments"}]}}"""
                .formatted(failedTask.getTaskRunName(), run.getPipelineRunName(), sa))));
        when(reader.serviceAccount(NS, sa)).thenReturn(Optional.of(new ServiceAccountBuilder().withNewMetadata().withName(sa)
                .withNamespace(NS).endMetadata().build()));
        when(reader.roleBindings(NS)).thenReturn(List.of(binding(sa, "pipeline-reader")));
        when(reader.role(NS, "pipeline-reader")).thenReturn(Optional.of(new RoleBuilder().withNewMetadata().withName("pipeline-reader").endMetadata()
                .withRules(new PolicyRuleBuilder().withApiGroups("tekton.dev").withResources("pipelineruns").withVerbs("get").build()).build()));
        when(reader.clusterRoleBindings()).thenThrow(new AgentToolException("list ClusterRoleBindings is not permitted for the agent (HTTP 403)"));
        answers.add(tool("get_taskrun_details", failedTask.getTaskRunName()));
        answers.add(tool("get_serviceaccount_details", sa));
        answers.add(report("The service account lacks get on deployments", "PERMISSION_DENIED", 0.9,
                1, "deployments.apps is forbidden: User cannot get resource deployments",
                2, "\"resources\":[\"pipelineruns\"]",
                2, "an invented quote"));
        long id = startTask();

        worker.processPending();

        AgentInvestigationEntity investigation = investigations.findById(id).orElseThrow();
        assertThat(investigation.getStatus()).isEqualTo(InvestigationStatus.COMPLETED);
        assertThat(investigation.getFailureCategory()).isEqualTo("PERMISSION_DENIED");
        assertThat(investigation.getConfidence()).isEqualByComparingTo("0.9");
        assertThat(investigation.getStepCount()).isEqualTo(3);
        assertThat(investigation.getToolCallCount()).isEqualTo(2);
        assertThat(investigation.getInputTokens()).isEqualTo(300);
        assertThat(investigation.getReport().evidence()).extracting(e -> e.step()).containsExactly(1, 2);
        assertThat(investigation.getReport().unverifiedEvidence()).isEqualTo(1);
        // The second question contained the result of the first tool, so the model could find the service account.
        assertThat(requests.get(1).userPrompt()).contains("\"serviceAccountName\":\"" + sa + "\"");
        assertThat(requests.get(2).userPrompt()).contains("list ClusterRoleBindings is not permitted");
        assertThat(jdbc.queryForObject("select count(*) from ai_invocation", Integer.class)).isEqualTo(3);

        mvc.perform(get("/api/v1/agent-investigations/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.rootCause").value("The service account lacks get on deployments"))
                .andExpect(jsonPath("$.report.evidence", hasSize(2)))
                // An answer without the ownership fields still completes; the owner is then unknown.
                .andExpect(jsonPath("$.report.ownership.team").value("UNKNOWN"))
                .andExpect(jsonPath("$.report.ownership.confidence").value("LOW"))
                .andExpect(jsonPath("$.report.missingInformation", hasSize(1)))
                .andExpect(jsonPath("$.report.recommendedActions[0]").value("Grant the missing permission"));
        mvc.perform(get("/api/v1/agent-investigations/{id}/steps", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(3)))
                .andExpect(jsonPath("$[0].toolName").value("get_taskrun_details"))
                .andExpect(jsonPath("$[0].status").value("SUCCEEDED"))
                .andExpect(jsonPath("$[0].arguments.namespace").value("ci"))
                .andExpect(jsonPath("$[1].toolName").value("get_serviceaccount_details"))
                .andExpect(jsonPath("$[2].action").value("FINAL_REPORT"))
                .andExpect(jsonPath("$[2].status").value("COMPLETED"));
    }

    @Test
    void unknownToolInvalidArgumentsAndDuplicatesAreRefusedAndTheAgentCanAdapt() {
        when(reader.taskRun(NS, failedTask.getTaskRunName())).thenReturn(Optional.of(EvidenceFixtures.failedTaskRun(failedTask.getTaskRunName(), "t1")));
        answers.add(tool("kubectl_delete_pod", "x"));
        answers.add(toolIn("get_pod_events", "deploy-pod", "kube-system"));
        answers.add(tool("get_deployment_status", "Bad Name; rm -rf /"));
        answers.add(tool("get_taskrun_details", failedTask.getTaskRunName()));
        answers.add(tool("get_taskrun_details", failedTask.getTaskRunName()));
        answers.add(report("Step test failed", "TEST_FAILURE", 0.5));
        long id = startTask();

        worker.processPending();

        List<AgentInvestigationStepEntity> recorded = steps.findByInvestigationIdOrderByStepNumber(id);
        assertThat(recorded).extracting(AgentInvestigationStepEntity::getStatus).containsExactly(
                StepStatus.REJECTED, StepStatus.REJECTED, StepStatus.REJECTED, StepStatus.SUCCEEDED, StepStatus.REJECTED, StepStatus.COMPLETED);
        assertThat(recorded.get(0).getErrorMessage()).isEqualTo("Unknown tool 'kubectl_delete_pod'");
        assertThat(recorded.get(1).getErrorMessage()).contains("namespace kube-system is not monitored");
        assertThat(recorded.get(2).getErrorMessage()).contains("must be a Kubernetes object name");
        assertThat(recorded.get(4).getErrorMessage()).contains("already called with the same arguments");
        verify(reader, times(1)).taskRun(anyString(), anyString());
        verify(reader, never()).eventsOf(anyString(), anyString());
        verify(reader, never()).deployment(anyString(), anyString());
        AgentInvestigationEntity investigation = investigations.findById(id).orElseThrow();
        assertThat(investigation.getStatus()).isEqualTo(InvestigationStatus.COMPLETED);
        assertThat(investigation.getToolCallCount()).isEqualTo(1);
        // Without verified evidence the confidence is limited.
        assertThat(investigation.getConfidence()).isEqualByComparingTo("0.3");
        assertThat(requests.get(1).userPrompt()).contains("Unknown tool 'kubectl_delete_pod'");
    }

    @Test
    void failedToolIsRecordedAndTheInvestigationContinues() {
        when(reader.deployment(NS, "demo-app")).thenThrow(new AgentToolException("get Deployment is not permitted for the agent (HTTP 403)"));
        when(reader.eventsOf(NS, "deploy-pod")).thenThrow(new IllegalStateException("boom"));
        answers.add(tool("get_deployment_status", "demo-app"));
        answers.add(tool("get_pod_events", "deploy-pod"));
        answers.add(report("Unknown", "UNKNOWN", 0.1));
        long id = startTask();

        worker.processPending();

        List<AgentInvestigationStepEntity> recorded = steps.findByInvestigationIdOrderByStepNumber(id);
        assertThat(recorded).extracting(AgentInvestigationStepEntity::getStatus)
                .containsExactly(StepStatus.FAILED, StepStatus.FAILED, StepStatus.COMPLETED);
        assertThat(recorded.get(0).getErrorMessage()).isEqualTo("get Deployment is not permitted for the agent (HTTP 403)");
        assertThat(recorded.get(1).getErrorMessage()).isEqualTo("The tool failed unexpectedly: IllegalStateException");
        assertThat(investigations.findById(id).orElseThrow().getStatus()).isEqualTo(InvestigationStatus.COMPLETED);
    }

    @Test
    void invalidAnswerIsShownToTheModel() {
        answers.add("this is not JSON");
        answers.add("{\"action\": \"RESTART_DEPLOYMENT\"}");
        answers.add(report("Unknown", "UNKNOWN", 0.1));
        long id = startTask();

        worker.processPending();

        assertThat(steps.findByInvestigationIdOrderByStepNumber(id)).extracting(AgentInvestigationStepEntity::getAction)
                .containsExactly(StepAction.INVALID, StepAction.INVALID, StepAction.FINAL_REPORT);
        assertThat(requests.get(1).userPrompt()).contains("Invalid answer: the answer is not valid JSON");
        assertThat(requests.get(2).userPrompt()).contains("action must be CALL_TOOL or FINAL_REPORT");
    }

    @Test
    void stepLimitEndsTheInvestigation() {
        for (int i = 0; i < 6; i++) {
            answers.add("not json " + i);
        }
        long id = startTask();

        worker.processPending();

        AgentInvestigationEntity investigation = investigations.findById(id).orElseThrow();
        assertThat(investigation.getStatus()).isEqualTo(InvestigationStatus.FAILED);
        assertThat(investigation.getErrorMessage()).isEqualTo("No final report within 6 steps");
        assertThat(investigation.getStepCount()).isEqualTo(6);
        assertThat(requests).hasSize(6);
        assertThat(requests.getLast().userPrompt()).contains("This is the last step: answer with action FINAL_REPORT now");
    }

    @Test
    void toolCallLimitForcesTheFinalReport() {
        when(reader.eventsOf(anyString(), anyString())).thenReturn(List.of());
        for (int i = 0; i < 5; i++) {
            answers.add(tool("get_pod_events", "pod-" + i));
        }
        long id = startTask();

        worker.processPending();

        AgentInvestigationEntity investigation = investigations.findById(id).orElseThrow();
        assertThat(investigation.getToolCallCount()).isEqualTo(4);
        verify(reader, times(4)).eventsOf(anyString(), anyString());
        assertThat(requests.get(4).userPrompt()).contains("This is the last step");
        assertThat(investigation.getStatus()).isEqualTo(InvestigationStatus.FAILED);
        assertThat(investigation.getErrorMessage()).startsWith("No final report within the limits");
        assertThat(steps.findByInvestigationIdOrderByStepNumber(id).getLast().getErrorMessage()).contains("budget is used up");
    }

    @Test
    void timeoutEndsTheInvestigation() {
        when(reader.eventsOf(anyString(), anyString())).thenAnswer(call -> {
            now = now.plus(Duration.ofSeconds(301));
            return List.of();
        });
        answers.add(tool("get_pod_events", "deploy-pod"));
        long id = startTask();

        worker.processPending();

        AgentInvestigationEntity investigation = investigations.findById(id).orElseThrow();
        assertThat(investigation.getStatus()).isEqualTo(InvestigationStatus.FAILED);
        assertThat(investigation.getErrorMessage()).startsWith("Timeout");
        assertThat(requests).hasSize(1);
    }

    @Test
    void providerErrorFailsTheInvestigationWithItsReason() {
        answers.add(new LlmProviderException(LlmProviderException.Category.RATE_LIMITED, "HTTP 429", null));
        long id = startTask();

        worker.processPending();

        assertThat(investigations.findById(id).orElseThrow().getErrorMessage()).isEqualTo("The LLM request failed: RATE_LIMITED: HTTP 429");
    }

    @Test
    void exhaustedQuotaStopsTheInvestigationAndRejectsNewOnes() throws Exception {
        for (int i = 0; i < 50; i++) {
            jdbc.update("insert into ai_quota (provider, created_at) select 'test-provider', ? where not exists (select 1 from ai_quota where provider = 'test-provider')",
                    java.sql.Timestamp.from(START));
            jdbc.update("insert into ai_invocation (provider, invoked_at) values ('test-provider', ?)", java.sql.Timestamp.from(START));
        }
        start("{\"taskRunName\": \"%s\", \"namespace\": \"ci\"}".formatted(failedTask.getTaskRunName()))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void secretsNeverReachTheModelOrTheDatabase() {
        when(reader.taskRun(NS, failedTask.getTaskRunName())).thenReturn(Optional.of(EvidenceFixtures.resource("""
                {"apiVersion": "tekton.dev/v1", "kind": "TaskRun", "metadata": {"name": "%s", "namespace": "ci", "uid": "t1"},
                 "status": {"conditions": [{"type": "Succeeded", "status": "False", "reason": "Failed",
                  "message": "login failed with password=hunter2secret and Authorization: Bearer abcdefghijklmnopqrstuvwxyz123"}]}}"""
                .formatted(failedTask.getTaskRunName()))));
        when(reader.pipelineRun(NS, run.getPipelineRunName())).thenReturn(Optional.of(EvidenceFixtures.resource("""
                {"apiVersion": "tekton.dev/v1", "kind": "PipelineRun", "metadata": {"name": "%s", "namespace": "ci", "uid": "p1"},
                 "spec": {"params": [{"name": "git-url", "value": "https://github.com/acme/demo"},
                                     {"name": "registry-token", "value": "plain-param-secret"}]},
                 "status": {}}""".formatted(run.getPipelineRunName()))));
        answers.add(tool("get_taskrun_details", failedTask.getTaskRunName()));
        answers.add(tool("get_pipelinerun_details", run.getPipelineRunName()));
        answers.add(report("Credentials password=hunter2secret were rejected", "REGISTRY_AUTHENTICATION", 0.4));
        long id = startTask();

        worker.processPending();

        String prompts = String.join("\n", requests.stream().map(LlmRequest::userPrompt).toList());
        assertThat(prompts).contains("[REDACTED]", "https://github.com/acme/demo")
                .doesNotContain("hunter2secret").doesNotContain("abcdefghijklmnopqrstuvwxyz123").doesNotContain("plain-param-secret");
        String stored = String.join(" ", jdbc.queryForList(
                "select coalesce(result_summary, '') || coalesce(reasoning, '') from agent_investigation_step", String.class))
                + String.join(" ", jdbc.queryForList("select root_cause || cast(report as varchar(10000)) from agent_investigation", String.class));
        assertThat(stored).doesNotContain("hunter2secret").doesNotContain("plain-param-secret").contains("[REDACTED]");
        assertThat(investigations.findById(id).orElseThrow().getStatus()).isEqualTo(InvestigationStatus.COMPLETED);
    }

    @Test
    void logToolUsesTheLogsStoredByEvidenceCollection() {
        FailureEvidenceEntity evidence = new FailureEvidenceEntity();
        evidence.setTaskRunId(failedTask.getId());
        evidence.setStatus(EvidenceStatus.COLLECTED);
        evidence.setPodName("deploy-pod");
        evidence.setCollectedAt(START);
        evidence.setCreatedAt(START);
        evidence.setUpdatedAt(START);
        evidenceRepository.save(evidence);
        ContainerEvidenceEntity container = new ContainerEvidenceEntity();
        container.setEvidenceId(evidence.getId());
        container.setContainerName("step-deploy");
        container.setContainerType(ContainerType.STEP);
        container.setOrdinal(0);
        container.setState(ContainerRunState.TERMINATED);
        container.setExitCode(1);
        container.setExitCodeSource(ExitCodeSource.POD);
        container.setReason("Error");
        container.setPrimaryFailure(true);
        container.setCreatedAt(START);
        container.setUpdatedAt(START);
        containerRepository.save(container);
        ContainerLogEntity log = new ContainerLogEntity();
        log.setContainerEvidenceId(container.getId());
        log.setStatus(LogStatus.COLLECTED);
        log.setContent("applying manifests\nError from server (Forbidden): deployments.apps \"demo\" is forbidden\ntoken=leakedtoken123");
        log.setExpiresAt(START.plus(Duration.ofDays(14)));
        log.setCreatedAt(START);
        log.setUpdatedAt(START);
        logRepository.save(log);
        answers.add(tool("get_taskrun_logs", failedTask.getTaskRunName()));
        answers.add(report("Forbidden", "PERMISSION_DENIED", 0.9, 1, "deployments.apps \"demo\" is forbidden"));
        long id = startTask();

        worker.processPending();

        assertThat(requests.get(1).userPrompt()).contains("logs stored by evidence collection", "\"container\":\"step-deploy\"",
                "\"exitCode\":1").doesNotContain("leakedtoken123");
        AgentInvestigationEntity investigation = investigations.findById(id).orElseThrow();
        assertThat(investigation.getReport().evidence()).hasSize(1);
        assertThat(investigation.getConfidence()).isEqualByComparingTo("0.9");
        verify(reader, never()).taskRun(anyString(), anyString());
    }

    // ---------------------------------------------------------------- persistence and workers

    @Test
    void onlyOneWorkerCanClaimAnInvestigation() {
        startTask();

        assertThat(store.claimNext()).isPresent();
        assertThat(store.claimNext()).isEmpty();
    }

    @Test
    void abandonedInvestigationIsFailedWithAReason() {
        long id = startTask();
        assertThat(store.claimNext()).isPresent();

        now = now.plus(Duration.ofHours(1));
        assertThat(store.claimNext()).isEmpty();

        AgentInvestigationEntity investigation = investigations.findById(id).orElseThrow();
        assertThat(investigation.getStatus()).isEqualTo(InvestigationStatus.FAILED);
        assertThat(investigation.getErrorMessage()).contains("stopped before the investigation finished");
    }

    @Test
    void lostClaimStopsTheWorkerWithoutWriting() {
        long id = startTask();
        AgentInvestigationStore.Claim claim = store.claimNext().orElseThrow();
        jdbc.update("update agent_investigation set claim_token = 'someone-else' where id = ?", id);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> store.fail(claim, "x", null, null, null))
                .isInstanceOf(AgentInvestigationStore.LostClaimException.class);
        assertThat(investigations.findById(id).orElseThrow().getStatus()).isEqualTo(InvestigationStatus.RUNNING);
    }

    @Test
    void schemaRejectsInconsistentRows() {
        long id = startTask();
        for (String change : List.of("status = 'UNKNOWN'", "target_type = 'POD'", "confidence = 2", "step_count = -1",
                "status = 'RUNNING'")) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                            jdbc.update("update agent_investigation set " + change + " where id = ?", id))
                    .as(change).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        }
    }

    // ---------------------------------------------------------------- reading

    @Test
    void listFiltersAndRejectsUnknownSorting() throws Exception {
        startTask();
        start("{\"pipelineRunName\": \"%s\", \"namespace\": \"ci\"}".formatted(run.getPipelineRunName()));

        mvc.perform(get("/api/v1/agent-investigations")).andExpect(jsonPath("$.totalElements").value(2));
        mvc.perform(get("/api/v1/agent-investigations").param("taskRunName", failedTask.getTaskRunName()))
                .andExpect(jsonPath("$.totalElements").value(1)).andExpect(jsonPath("$.content[0].targetType").value("TASK_RUN"));
        mvc.perform(get("/api/v1/agent-investigations").param("pipelineRunName", run.getPipelineRunName()))
                .andExpect(jsonPath("$.totalElements").value(1)).andExpect(jsonPath("$.content[0].targetType").value("PIPELINE_RUN"));
        mvc.perform(get("/api/v1/agent-investigations").param("namespace", "staging")).andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(get("/api/v1/agent-investigations").param("status", "PENDING")).andExpect(jsonPath("$.totalElements").value(2));
        mvc.perform(get("/api/v1/agent-investigations").param("sort", "claimToken,desc")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/agent-investigations").param("status", "BROKEN")).andExpect(status().isBadRequest());
    }

    @Test
    void unknownInvestigationIsNotFound() throws Exception {
        mvc.perform(get("/api/v1/agent-investigations/999999")).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/agent-investigations/999999/steps")).andExpect(status().isNotFound());
    }

    // ---------------------------------------------------------------- helpers

    private long startTask() {
        try {
            return idOf(start("{\"taskRunName\": \"%s\", \"namespace\": \"ci\"}".formatted(failedTask.getTaskRunName()))
                    .andExpect(status().isAccepted()));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private ResultActions start(String body) throws Exception {
        return mvc.perform(post("/api/v1/agent-investigations").contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private static long idOf(ResultActions result) throws Exception {
        String json = result.andReturn().getResponse().getContentAsString();
        return Long.parseLong(json.replaceAll("(?s).*\"id\":(\\d+).*", "$1"));
    }

    private static TaskRunEntity failed(TaskRunEntity task) {
        task.setFailureReason("Failed");
        task.setFailureMessage("\"step-deploy\" exited with code 1: Error");
        return task;
    }

    private static RoleBinding binding(String serviceAccount, String role) {
        return new RoleBindingBuilder().withNewMetadata().withName(role + "-binding").endMetadata()
                .addNewSubject().withKind("ServiceAccount").withName(serviceAccount).withNamespace(NS).endSubject()
                .withNewRoleRef().withKind("Role").withName(role).withApiGroup("rbac.authorization.k8s.io").endRoleRef()
                .build();
    }

    static String tool(String name, String resource) {
        return toolIn(name, resource, NS);
    }

    static String toolIn(String name, String resource, String namespace) {
        return """
                {"reasoning": "Next I read %1$s", "action": "CALL_TOOL", "toolName": "%1$s", "resourceName": "%2$s", "namespace": "%3$s",
                 "report": {"summary": "", "rootCause": "", "failureCategory": "UNKNOWN", "confidence": 0, "evidence": [], "recommendedActions": []}}"""
                .formatted(name, resource, namespace);
    }

    static String finalReport(String rootCause, double confidence) {
        return report(rootCause, "UNKNOWN", confidence);
    }

    /** @param evidence pairs of step number and quote */
    static String report(String rootCause, String category, double confidence, Object... evidence) {
        StringBuilder items = new StringBuilder();
        for (int i = 0; i + 1 < evidence.length; i += 2) {
            items.append(items.isEmpty() ? "" : ",").append("{\"step\": ").append(evidence[i]).append(", \"observation\": ")
                    .append(tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(evidence[i + 1])).append('}');
        }
        return """
                {"reasoning": "The evidence is sufficient", "action": "FINAL_REPORT", "toolName": null, "resourceName": null, "namespace": null,
                 "report": {"summary": "%1$s", "rootCause": "%1$s", "failureCategory": "%2$s", "confidence": %3$s, "evidence": [%4$s],
                            "recommendedActions": ["Grant the missing permission"]}}"""
                .formatted(rootCause, category, confidence, items);
    }
}
