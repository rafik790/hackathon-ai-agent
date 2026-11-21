package com.hackathon.agent.kubernetes;

import static com.hackathon.agent.support.TektonFixtures.pipelineRun;
import static com.hackathon.agent.support.TektonFixtures.taskRun;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hackathon.agent.config.MonitoringProperties;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.base.CustomResourceDefinitionContext;
import io.fabric8.kubernetes.client.dsl.base.ResourceDefinitionContext;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Exercises the Tekton client against Fabric8's in-memory Kubernetes API server (no real cluster).
 */
@EnableKubernetesMockClient(crud = true)
class TektonResourceClientTest {

    private static final CustomResourceDefinitionContext PIPELINE_RUNS = new CustomResourceDefinitionContext.Builder()
            .withGroup("tekton.dev").withVersion("v1").withKind("PipelineRun").withPlural("pipelineruns")
            .withScope("Namespaced").build();
    private static final CustomResourceDefinitionContext TASK_RUNS = new CustomResourceDefinitionContext.Builder()
            .withGroup("tekton.dev").withVersion("v1").withKind("TaskRun").withPlural("taskruns")
            .withScope("Namespaced").build();

    KubernetesMockServer server;
    KubernetesClient client;

    @BeforeEach
    void registerTektonCrds() {
        server.expectCustomResource(PIPELINE_RUNS);
        server.expectCustomResource(TASK_RUNS);
    }

    @Test
    void listsPipelineRunsAndTaskRunsOfTheRequestedNamespaceOnly() {
        create(PIPELINE_RUNS, pipelineRun("u1", "build-1", "ci", "build", "True", "Succeeded", null, null, null));
        create(PIPELINE_RUNS, pipelineRun("u2", "build-2", "ci", "build", "Unknown", "Running", null, null, null));
        create(PIPELINE_RUNS, pipelineRun("u3", "deploy-1", "staging", "deploy", "True", "Succeeded", null, null, null));
        create(TASK_RUNS, taskRun("t1", "build-1-clone", "ci", "u1", "build-1", "build", "clone", "True",
                "Succeeded", null, null, null));

        TektonResourceClient tektonClient = new TektonResourceClient(client, properties(null));

        List<GenericKubernetesResource> pipelineRuns = tektonClient.listPipelineRuns("ci");
        assertThat(pipelineRuns).extracting(r -> r.getMetadata().getName())
                .containsExactlyInAnyOrder("build-1", "build-2");
        assertThat(tektonClient.listTaskRuns("ci")).extracting(r -> r.getMetadata().getName())
                .containsExactly("build-1-clone");
        assertThat(tektonClient.listTaskRuns("staging")).isEmpty();
    }

    @Test
    void appliesConfiguredLabelSelector() {
        GenericKubernetesResource matching = pipelineRun("u1", "build-1", "ci", "build", "True", null, null, null, null);
        GenericKubernetesResource other = pipelineRun("u2", "other-1", "ci", "other", "True", null, null, null, null);
        create(PIPELINE_RUNS, matching);
        create(PIPELINE_RUNS, other);

        TektonResourceClient tektonClient = new TektonResourceClient(client, properties("tekton.dev/pipeline=build"));

        assertThat(tektonClient.listPipelineRuns("ci")).extracting(r -> r.getMetadata().getName())
                .containsExactly("build-1");
    }

    @Test
    void wrapsApiErrorsInKubernetesAccessException() {
        server.expect().get().withPath("/apis/tekton.dev/v1/namespaces/forbidden/pipelineruns?limit=50")
                .andReturn(403, "{\"kind\":\"Status\",\"code\":403,\"message\":\"forbidden\"}").always();

        TektonResourceClient tektonClient = new TektonResourceClient(client, properties(null));

        assertThatThrownBy(() -> tektonClient.listPipelineRuns("forbidden"))
                .isInstanceOf(KubernetesAccessException.class)
                .hasMessageContaining("pipelineruns")
                .hasMessageContaining("forbidden");
    }

    private void create(ResourceDefinitionContext context, GenericKubernetesResource resource) {
        client.genericKubernetesResources(context).inNamespace(resource.getMetadata().getNamespace()).resource(resource).create();
    }

    private static MonitoringProperties properties(String labelSelector) {
        return new MonitoringProperties(true, 15000, 0, List.of("ci"), labelSelector, 50, null);
    }
}
