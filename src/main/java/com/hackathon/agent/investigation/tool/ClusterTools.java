package com.hackathon.agent.investigation.tool;

import static com.hackathon.agent.investigation.tool.Resources.ordered;

import com.hackathon.agent.investigation.tool.RunTools.Tool;
import io.fabric8.kubernetes.api.model.Event;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.ServiceAccount;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentStatus;
import io.fabric8.kubernetes.api.model.rbac.PolicyRule;
import io.fabric8.kubernetes.api.model.rbac.RoleRef;
import io.fabric8.kubernetes.api.model.rbac.Subject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Tools about Kubernetes objects: Pod events, Deployment status and the permissions of a service account. They only
 * read; in particular, permissions are inspected and never changed.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnBooleanProperty(name = "agent.investigation.enabled")
public class ClusterTools {

    static final int MAX_EVENTS = 20;
    static final int MAX_BINDINGS = 20;
    static final int MAX_RULES = 30;

    @Bean
    AgentTool getPodEvents(AgentKubernetesReader reader) {
        return new Tool("get_pod_events",
                "Kubernetes events of a Pod, newest first: scheduling, image pulls, mounts, probes, evictions, OOM kills. The Pod name of a TaskRun is in its details.",
                "Pod name",
                arguments -> {
                    List<Event> events = reader.eventsOf(arguments.namespace(), arguments.resourceName());
                    List<Map<String, Object>> result = events.stream()
                            .sorted(Comparator.comparing(ClusterTools::eventTime, Comparator.nullsLast(Comparator.reverseOrder())))
                            .limit(MAX_EVENTS)
                            .map(e -> ordered("type", e.getType(), "reason", e.getReason(), "message", e.getMessage(),
                                    "count", e.getCount(), "timestamp", eventTime(e)))
                            .toList();
                    Map<String, Object> output = ordered("pod", arguments.resourceName(), "eventCount", events.size());
                    output.put("events", result);
                    if (events.isEmpty()) {
                        output.put("note", "No events found. Kubernetes keeps events for about one hour.");
                    }
                    return output;
                });
    }

    @Bean
    AgentTool getDeploymentStatus(AgentKubernetesReader reader) {
        return new Tool("get_deployment_status",
                "Desired, ready, available and updated replicas, conditions, container images and whether the rollout is complete for a Deployment.",
                "Deployment name",
                arguments -> {
                    Deployment deployment = reader.deployment(arguments.namespace(), arguments.resourceName())
                            .orElseThrow(() -> new AgentToolException("Deployment " + arguments.resourceName() + " not found"));
                    DeploymentStatus status = deployment.getStatus() == null ? new DeploymentStatus() : deployment.getStatus();
                    int desired = deployment.getSpec() == null || deployment.getSpec().getReplicas() == null ? 1 : deployment.getSpec().getReplicas();
                    Map<String, Object> result = ordered("name", deployment.getMetadata().getName(),
                            "desiredReplicas", desired,
                            "readyReplicas", zero(status.getReadyReplicas()),
                            "availableReplicas", zero(status.getAvailableReplicas()),
                            "updatedReplicas", zero(status.getUpdatedReplicas()),
                            "unavailableReplicas", zero(status.getUnavailableReplicas()),
                            "rolloutComplete", rolloutComplete(deployment, status, desired));
                    result.put("conditions", status.getConditions() == null ? List.of() : status.getConditions().stream()
                            .map(c -> ordered("type", c.getType(), "status", c.getStatus(), "reason", c.getReason(),
                                    "message", c.getMessage(), "lastUpdateTime", c.getLastUpdateTime()))
                            .toList());
                    if (deployment.getSpec() != null && deployment.getSpec().getTemplate() != null
                            && deployment.getSpec().getTemplate().getSpec() != null) {
                        result.put("serviceAccountName", deployment.getSpec().getTemplate().getSpec().getServiceAccountName());
                        result.put("containers", deployment.getSpec().getTemplate().getSpec().getContainers().stream()
                                .map(c -> ordered("name", c.getName(), "image", c.getImage())).toList());
                    }
                    return result;
                });
    }

    @Bean
    AgentTool getServiceAccountDetails(AgentKubernetesReader reader) {
        return new Tool("get_serviceaccount_details",
                "Metadata of a service account and the RoleBindings and ClusterRoleBindings that grant it permissions, with the rules of the referenced Roles and ClusterRoles. Use it when the evidence shows Forbidden or permission errors.",
                "Service account name",
                arguments -> serviceAccount(reader, arguments.namespace(), arguments.resourceName()));
    }

    private static Map<String, Object> serviceAccount(AgentKubernetesReader reader, String namespace, String name) {
        ServiceAccount account = reader.serviceAccount(namespace, name)
                .orElseThrow(() -> new AgentToolException("ServiceAccount " + name + " not found"));
        ObjectMeta meta = account.getMetadata();
        // Secret names are left out: they are not needed to judge permissions.
        Map<String, Object> result = ordered("name", meta.getName(), "namespace", meta.getNamespace(),
                "creationTimestamp", meta.getCreationTimestamp(),
                "automountServiceAccountToken", account.getAutomountServiceAccountToken());
        List<Map<String, Object>> bindings = new ArrayList<>();
        reader.roleBindings(namespace).stream()
                .filter(binding -> grants(binding.getSubjects(), namespace, name))
                .limit(MAX_BINDINGS)
                .forEach(binding -> bindings.add(binding("RoleBinding", binding.getMetadata().getName(), binding.getRoleRef(),
                        rules(reader, namespace, binding.getRoleRef()))));
        result.put("roleBindings", bindings);
        try {
            List<Map<String, Object>> clusterBindings = new ArrayList<>();
            reader.clusterRoleBindings().stream()
                    .filter(binding -> grants(binding.getSubjects(), namespace, name))
                    .limit(MAX_BINDINGS)
                    .forEach(binding -> clusterBindings.add(binding("ClusterRoleBinding", binding.getMetadata().getName(),
                            binding.getRoleRef(), rules(reader, namespace, binding.getRoleRef()))));
            result.put("clusterRoleBindings", clusterBindings);
        } catch (AgentToolException e) {
            result.put("clusterRoleBindings", "unknown: " + e.getMessage());
        }
        return result;
    }

    /** Whether a binding grants something to the service account, directly or through its groups. */
    static boolean grants(List<Subject> subjects, String namespace, String name) {
        return subjects != null && subjects.stream().anyMatch(s ->
                ("ServiceAccount".equals(s.getKind()) && name.equals(s.getName())
                        && Objects.requireNonNullElse(s.getNamespace(), namespace).equals(namespace))
                        || ("Group".equals(s.getKind()) && ("system:serviceaccounts".equals(s.getName())
                        || ("system:serviceaccounts:" + namespace).equals(s.getName()))));
    }

    private static Map<String, Object> binding(String kind, String name, RoleRef roleRef, Object rules) {
        Map<String, Object> result = ordered("kind", kind, "name", name, "roleKind", roleRef.getKind(), "roleName", roleRef.getName());
        result.put("rules", rules);
        return result;
    }

    private static Object rules(AgentKubernetesReader reader, String namespace, RoleRef roleRef) {
        try {
            List<PolicyRule> rules = "ClusterRole".equals(roleRef.getKind())
                    ? reader.clusterRole(roleRef.getName()).map(r -> r.getRules()).orElse(null)
                    : reader.role(namespace, roleRef.getName()).map(r -> r.getRules()).orElse(null);
            if (rules == null) {
                return "unknown: " + roleRef.getKind() + " " + roleRef.getName() + " not found";
            }
            return rules.stream().limit(MAX_RULES)
                    .map(r -> ordered("apiGroups", r.getApiGroups(), "resources", r.getResources(),
                            "resourceNames", r.getResourceNames(), "verbs", r.getVerbs()))
                    .toList();
        } catch (AgentToolException e) {
            return "unknown: " + e.getMessage();
        }
    }

    private static String eventTime(Event event) {
        if (event.getLastTimestamp() != null) {
            return event.getLastTimestamp();
        }
        if (event.getEventTime() != null && event.getEventTime().getTime() != null) {
            return event.getEventTime().getTime();
        }
        return event.getFirstTimestamp();
    }

    private static boolean rolloutComplete(Deployment deployment, DeploymentStatus status, int desired) {
        Long generation = deployment.getMetadata().getGeneration();
        Long observed = status.getObservedGeneration();
        return (generation == null || (observed != null && observed >= generation))
                && zero(status.getUpdatedReplicas()) == desired && zero(status.getAvailableReplicas()) == desired
                && zero(status.getReplicas()) == desired;
    }

    private static int zero(Integer value) {
        return value == null ? 0 : value;
    }
}
