package com.hackathon.agent.investigation.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.hackathon.agent.config.KubernetesProperties;
import com.hackathon.agent.config.MonitoringProperties;
import com.hackathon.agent.entity.PipelineRunEntity;
import com.hackathon.agent.enums.ExecutionStatus;
import com.hackathon.agent.evidence.sanitizer.LogSanitizer;
import com.hackathon.agent.investigation.AgentInvestigationProperties;
import com.hackathon.agent.investigation.repository.AgentPipelineRunLookup;
import com.hackathon.agent.investigation.tool.AgentTool.ToolArguments;
import com.hackathon.agent.repository.TaskRunRepository;
import com.hackathon.agent.support.TestEntities;
import io.fabric8.kubernetes.api.model.EventBuilder;
import io.fabric8.kubernetes.api.model.EventListBuilder;
import io.fabric8.kubernetes.api.model.ServiceAccountBuilder;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import io.fabric8.kubernetes.api.model.rbac.ClusterRoleBuilder;
import io.fabric8.kubernetes.api.model.rbac.RoleBindingBuilder;
import io.fabric8.kubernetes.api.model.rbac.RoleBindingListBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer;
import io.fabric8.mockwebserver.http.RecordedRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Tools against Fabric8's in-memory API server: what they return, and that they only ever read.
 */
@EnableKubernetesMockClient
class AgentToolsTest {

    static final String NS = "ci";

    KubernetesMockServer server;
    KubernetesClient client;

    private final AgentPipelineRunLookup pipelineRuns = mock(AgentPipelineRunLookup.class);
    private final TaskRunRepository taskRuns = mock(TaskRunRepository.class);

    @AfterEach
    void onlyReadRequestsWereSent() throws InterruptedException {
        for (String request : requests()) {
            assertThat(request).as("read-only").startsWith("GET ");
        }
    }

    @Test
    void serviceAccountToolShowsBindingsAndRulesAndCopesWithoutClusterPermissions() {
        String sa = "builder";
        server.expect().get().withPath("/api/v1/namespaces/ci/serviceaccounts/builder")
                .andReturn(200, new ServiceAccountBuilder().withNewMetadata().withName(sa).withNamespace(NS).endMetadata()
                        .addNewSecret().withName("builder-token-secret").endSecret().build()).once();
        server.expect().get().withPath("/apis/rbac.authorization.k8s.io/v1/namespaces/ci/rolebindings")
                .andReturn(200, new RoleBindingListBuilder().withItems(
                        binding("grants-builder", "ServiceAccount", sa, "ClusterRole", "edit"),
                        binding("someone-else", "ServiceAccount", "other", "Role", "admin")).build()).once();
        server.expect().get().withPath("/apis/rbac.authorization.k8s.io/v1/clusterroles/edit")
                .andReturn(200, new ClusterRoleBuilder().withNewMetadata().withName("edit").endMetadata()
                        .addNewRule().withApiGroups("apps").withResources("deployments").withVerbs("get", "patch").endRule().build()).once();
        server.expect().get().withPath("/apis/rbac.authorization.k8s.io/v1/clusterrolebindings").andReturn(403, "{}").once();

        String result = registry().execute(new ClusterTools().getServiceAccountDetails(reader()), new ToolArguments(sa, NS));

        assertThat(result).contains("\"roleBindings\":[{\"kind\":\"RoleBinding\",\"name\":\"grants-builder\"")
                .contains("\"resources\":[\"deployments\"]", "\"verbs\":[\"get\",\"patch\"]")
                .contains("unknown: list ClusterRoleBindings is not permitted for the agent (HTTP 403)")
                .doesNotContain("someone-else").doesNotContain("builder-token-secret");
    }

    @Test
    void grantsThroughGroupsCount() {
        var subjects = binding("b", "Group", "system:serviceaccounts:ci", "Role", "r").getSubjects();

        assertThat(ClusterTools.grants(subjects, NS, "any")).isTrue();
        assertThat(ClusterTools.grants(subjects, "other", "any")).isFalse();
    }

    @Test
    void podEventsAreSelectedByTheServerAndNewestFirst() {
        server.expect().get().withPath("/api/v1/namespaces/ci/events?fieldSelector=involvedObject.name%3Dbuild-pod")
                .andReturn(200, new EventListBuilder().withItems(
                        event("Pulling", "Pulling image demo:1", "2026-11-27T10:00:00Z"),
                        event("Failed", "Failed to pull image \"demo:1\": not found", "2026-11-27T10:00:05Z")).build()).once();

        String result = registry().execute(new ClusterTools().getPodEvents(reader()), new ToolArguments("build-pod", NS));

        assertThat(result).contains("\"eventCount\":2").contains("\"reason\":\"Failed\"");
        assertThat(result.indexOf("Failed to pull")).isLessThan(result.indexOf("Pulling image"));
    }

    @Test
    void deploymentStatusShowsAnIncompleteRollout() {
        server.expect().get().withPath("/apis/apps/v1/namespaces/ci/deployments/demo")
                .andReturn(200, new DeploymentBuilder().withNewMetadata().withName("demo").withGeneration(3L).endMetadata()
                        .withNewSpec().withReplicas(2).withNewTemplate().withNewSpec()
                        .addNewContainer().withName("app").withImage("ghcr.io/acme/demo:2").endContainer().endSpec().endTemplate().endSpec()
                        .withNewStatus().withObservedGeneration(3L).withReplicas(2).withReadyReplicas(1).withAvailableReplicas(1)
                        .withUpdatedReplicas(1).withUnavailableReplicas(1)
                        .addNewCondition().withType("Progressing").withStatus("False").withReason("ProgressDeadlineExceeded").endCondition()
                        .endStatus().build()).once();

        String result = registry().execute(new ClusterTools().getDeploymentStatus(reader()), new ToolArguments("demo", NS));

        assertThat(result).contains("\"desiredReplicas\":2", "\"availableReplicas\":1", "\"rolloutComplete\":false",
                "ProgressDeadlineExceeded", "ghcr.io/acme/demo:2");
    }

    @Test
    void missingObjectsAreReportedAsToolErrors() {
        assertThatThrownBy(() -> new ClusterTools().getDeploymentStatus(reader()).execute(new ToolArguments("missing", NS)))
                .isInstanceOf(AgentToolException.class).hasMessage("Deployment missing not found");
    }

    @Test
    void pipelineRunDetailsHideSensitiveParameters() {
        server.expect().get().withPath("/apis/tekton.dev/v1/namespaces/ci/pipelineruns/build-1").andReturn(200, """
                {"apiVersion": "tekton.dev/v1", "kind": "PipelineRun", "metadata": {"name": "build-1", "namespace": "ci",
                 "labels": {"tekton.dev/pipeline": "build"}},
                 "spec": {"params": [{"name": "git-revision", "value": "abc123"}, {"name": "docker-password", "value": "s3cr3t"},
                                     {"name": "api_key", "value": "k-123"}],
                          "taskRunTemplate": {"serviceAccountName": "builder"}},
                 "status": {"conditions": [{"type": "Succeeded", "status": "False", "reason": "Failed", "message": "Tasks Completed: 2 (Failed: 1)"}],
                            "childReferences": [{"kind": "TaskRun", "name": "build-1-compile", "pipelineTaskName": "compile"}]}}""").once();
        when(pipelineRuns.findFirstByNamespaceAndPipelineRunNameOrderByIdDesc(NS, "build-1")).thenReturn(Optional.empty());

        String result = registry().execute(tools().getPipelineRunDetails(reader(), pipelineRuns, taskRuns), new ToolArguments("build-1", NS));

        assertThat(result).contains("\"pipeline\":\"build\"", "\"value\":\"abc123\"", "\"serviceAccountName\":\"builder\"",
                "build-1-compile", "Tasks Completed: 2 (Failed: 1)")
                .doesNotContain("s3cr3t").doesNotContain("k-123");
    }

    @Test
    void previousSuccessfulRunComesFromStoredData() {
        PipelineRunEntity success = TestEntities.pipelineRun("p0", NS, "build", ExecutionStatus.SUCCEEDED, TestEntities.NOW, 60_000L);
        when(pipelineRuns.findFirstByNamespaceAndPipelineNameAndStatusOrderByCompletionTimeDescIdDesc(NS, "build", ExecutionStatus.SUCCEEDED))
                .thenReturn(Optional.of(success));
        when(taskRuns.findByPipelineRun("p0", NS, success.getPipelineRunName())).thenReturn(List.of(
                TestEntities.taskRun("t0", success, "compile", ExecutionStatus.SUCCEEDED, TestEntities.NOW, 1000L)));

        String result = registry().execute(tools().getPreviousSuccessfulPipelineRun(reader(), pipelineRuns, taskRuns),
                new ToolArguments("build", NS));

        assertThat(result).contains("\"pipelineRun\":\"build-p0\"", "\"status\":\"SUCCEEDED\"", "no longer exists in the cluster");
        assertThatThrownBy(() -> tools().getPreviousSuccessfulPipelineRun(reader(), pipelineRuns, taskRuns)
                .execute(new ToolArguments("other", NS))).hasMessage("No successful run of pipeline other is stored");
    }

    @Test
    void readerOffersOnlyReadOperations() {
        assertThat(java.util.Arrays.stream(AgentKubernetesReader.class.getDeclaredMethods())
                .filter(m -> java.lang.reflect.Modifier.isPublic(m.getModifiers())).map(java.lang.reflect.Method::getName))
                .allMatch(name -> List.of("pipelineRun", "taskRun", "pod", "eventsOf", "deployment", "serviceAccount", "roleBindings",
                        "clusterRoleBindings", "role", "clusterRole").contains(name));
    }

    // ---------------------------------------------------------------- helpers

    private AgentKubernetesReader reader() {
        return new AgentKubernetesReader(client, new KubernetesProperties(null, null, null, 5000, 5000), monitoring());
    }

    private static RunTools tools() {
        return new RunTools();
    }

    private static AgentToolRegistry registry() {
        return new AgentToolRegistry(List.of(), monitoring(), new LogSanitizer(),
                new AgentInvestigationProperties(true, 8, 10, 300, 6000, 8000, 60000, 2000, 900));
    }

    static MonitoringProperties monitoring() {
        return new MonitoringProperties(true, 15000, 0, List.of(NS, "staging"), null, 100, null);
    }

    private static io.fabric8.kubernetes.api.model.rbac.RoleBinding binding(String name, String subjectKind, String subject,
                                                                             String roleKind, String role) {
        return new RoleBindingBuilder().withNewMetadata().withName(name).endMetadata()
                .addNewSubject().withKind(subjectKind).withName(subject).withNamespace(NS).endSubject()
                .withNewRoleRef().withKind(roleKind).withName(role).withApiGroup("rbac.authorization.k8s.io").endRoleRef().build();
    }

    private static io.fabric8.kubernetes.api.model.Event event(String reason, String message, String time) {
        return new EventBuilder().withNewMetadata().withName(reason).endMetadata().withType("Warning").withReason(reason)
                .withMessage(message).withLastTimestamp(time).build();
    }

    private List<String> requests() throws InterruptedException {
        List<String> requests = new ArrayList<>();
        RecordedRequest request;
        while ((request = server.takeRequest(100, TimeUnit.MILLISECONDS)) != null) {
            requests.add(request.getMethod() + " " + request.getPath());
        }
        return requests;
    }
}
