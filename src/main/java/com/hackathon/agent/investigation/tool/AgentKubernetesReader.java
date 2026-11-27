package com.hackathon.agent.investigation.tool;

import com.hackathon.agent.config.KubernetesProperties;
import com.hackathon.agent.config.MonitoringProperties;
import io.fabric8.kubernetes.api.model.Event;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.ServiceAccount;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.rbac.ClusterRole;
import io.fabric8.kubernetes.api.model.rbac.ClusterRoleBinding;
import io.fabric8.kubernetes.api.model.rbac.Role;
import io.fabric8.kubernetes.api.model.rbac.RoleBinding;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.RequestConfigBuilder;
import io.fabric8.kubernetes.client.dsl.base.ResourceDefinitionContext;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.stereotype.Component;

/**
 * The only access of the investigation agent to Kubernetes, and read-only by construction: it offers nothing but
 * {@code get} and {@code list} of the resources the tools need.
 * <p>
 * Uses a view of the existing client with its own request timeout and without automatic retries; the existing
 * client and its configuration are not changed.
 */
@Component
@ConditionalOnBooleanProperty(name = "agent.investigation.enabled")
public class AgentKubernetesReader {

    private final KubernetesClient client;
    private final ResourceDefinitionContext pipelineRuns;
    private final ResourceDefinitionContext taskRuns;

    public AgentKubernetesReader(KubernetesClient client, KubernetesProperties kubernetes, MonitoringProperties monitoring) {
        // Not a bean and never closed: closing it would close the connection shared with the collector.
        this.client = client.newClient(new RequestConfigBuilder(client.getConfiguration().getRequestConfig())
                        .withRequestTimeout(kubernetes.requestTimeoutMs())
                        .withRequestRetryBackoffLimit(0)
                        .build())
                .adapt(KubernetesClient.class);
        this.pipelineRuns = tekton(monitoring, "PipelineRun", "pipelineruns");
        this.taskRuns = tekton(monitoring, "TaskRun", "taskruns");
    }

    private static ResourceDefinitionContext tekton(MonitoringProperties monitoring, String kind, String plural) {
        return new ResourceDefinitionContext.Builder()
                .withGroup(monitoring.tekton().group())
                .withVersion(monitoring.tekton().apiVersion())
                .withKind(kind)
                .withPlural(plural)
                .withNamespaced(true)
                .build();
    }

    public Optional<GenericKubernetesResource> pipelineRun(String namespace, String name) {
        return Optional.ofNullable(call("get PipelineRun",
                () -> client.genericKubernetesResources(pipelineRuns).inNamespace(namespace).withName(name).get()));
    }

    public Optional<GenericKubernetesResource> taskRun(String namespace, String name) {
        return Optional.ofNullable(call("get TaskRun",
                () -> client.genericKubernetesResources(taskRuns).inNamespace(namespace).withName(name).get()));
    }

    public Optional<Pod> pod(String namespace, String name) {
        return Optional.ofNullable(call("get Pod", () -> client.pods().inNamespace(namespace).withName(name).get()));
    }

    /** Events about one object, selected by the API server. */
    public List<Event> eventsOf(String namespace, String objectName) {
        return call("list Events", () -> client.v1().events().inNamespace(namespace)
                .withField("involvedObject.name", objectName).list().getItems());
    }

    public Optional<Deployment> deployment(String namespace, String name) {
        return Optional.ofNullable(call("get Deployment",
                () -> client.apps().deployments().inNamespace(namespace).withName(name).get()));
    }

    public Optional<ServiceAccount> serviceAccount(String namespace, String name) {
        return Optional.ofNullable(call("get ServiceAccount",
                () -> client.serviceAccounts().inNamespace(namespace).withName(name).get()));
    }

    public List<RoleBinding> roleBindings(String namespace) {
        return call("list RoleBindings", () -> client.rbac().roleBindings().inNamespace(namespace).list().getItems());
    }

    /** Cluster-scoped: often not permitted for the agent, which callers must handle. */
    public List<ClusterRoleBinding> clusterRoleBindings() {
        return call("list ClusterRoleBindings", () -> client.rbac().clusterRoleBindings().list().getItems());
    }

    public Optional<Role> role(String namespace, String name) {
        return Optional.ofNullable(call("get Role", () -> client.rbac().roles().inNamespace(namespace).withName(name).get()));
    }

    /** Cluster-scoped: often not permitted for the agent, which callers must handle. */
    public Optional<ClusterRole> clusterRole(String name) {
        return Optional.ofNullable(call("get ClusterRole", () -> client.rbac().clusterRoles().withName(name).get()));
    }

    private static <T> T call(String operation, Supplier<T> request) {
        try {
            return request.get();
        } catch (KubernetesClientException e) {
            throw new AgentToolException(describe(operation, e.getCode()), e);
        } catch (RuntimeException e) {
            throw new AgentToolException(operation + " failed: " + e.getClass().getSimpleName(), e);
        }
    }

    /** Only the operation and the HTTP status: response bodies may contain anything. */
    static String describe(String operation, int code) {
        return switch (code) {
            case 401, 403 -> operation + " is not permitted for the agent (HTTP " + code + ")";
            case 0 -> operation + " failed: the Kubernetes API could not be reached";
            default -> operation + " failed: HTTP " + code;
        };
    }
}
