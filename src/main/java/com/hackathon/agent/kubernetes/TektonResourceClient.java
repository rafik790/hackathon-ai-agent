package com.hackathon.agent.kubernetes;

import com.hackathon.agent.config.MonitoringProperties;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.GenericKubernetesResourceList;
import io.fabric8.kubernetes.api.model.ListOptions;
import io.fabric8.kubernetes.api.model.ListOptionsBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.dsl.base.ResourceDefinitionContext;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Read-only access to Tekton custom resources.
 * <p>
 * Uses Fabric8 generic resources so that no Tekton model classes are required and the Tekton API
 * version stays configurable. Lists are paginated (limit/continue) to bound memory use.
 * This is the only class that talks to the Kubernetes API.
 */
@Component
public class TektonResourceClient {

    public static final String PIPELINE_RUN_KIND = "PipelineRun";
    public static final String TASK_RUN_KIND = "TaskRun";

    private final KubernetesClient client;
    private final MonitoringProperties properties;
    private final ResourceDefinitionContext pipelineRunContext;
    private final ResourceDefinitionContext taskRunContext;

    public TektonResourceClient(KubernetesClient client, MonitoringProperties properties) {
        this.client = client;
        this.properties = properties;
        this.pipelineRunContext = context(PIPELINE_RUN_KIND, "pipelineruns");
        this.taskRunContext = context(TASK_RUN_KIND, "taskruns");
    }

    public List<GenericKubernetesResource> listPipelineRuns(String namespace) {
        return list(pipelineRunContext, namespace);
    }

    public List<GenericKubernetesResource> listTaskRuns(String namespace) {
        return list(taskRunContext, namespace);
    }

    private List<GenericKubernetesResource> list(ResourceDefinitionContext context, String namespace) {
        List<GenericKubernetesResource> items = new ArrayList<>();
        String continueToken = null;
        try {
            do {
                GenericKubernetesResourceList page = client.genericKubernetesResources(context)
                        .inNamespace(namespace)
                        .list(listOptions(continueToken));
                if (page.getItems() != null) {
                    items.addAll(page.getItems());
                }
                continueToken = page.getMetadata() == null ? null : page.getMetadata().getContinue();
            } while (continueToken != null && !continueToken.isBlank());
        } catch (KubernetesClientException e) {
            throw new KubernetesAccessException("Failed to list %s in namespace '%s' (HTTP %d): %s"
                    .formatted(context.getPlural(), namespace, e.getCode(), e.getMessage()), e);
        } catch (RuntimeException e) {
            // Timeouts and I/O problems may surface as other runtime exceptions.
            throw new KubernetesAccessException("Failed to list %s in namespace '%s': %s"
                    .formatted(context.getPlural(), namespace, e.getMessage()), e);
        }
        return items;
    }

    private ListOptions listOptions(String continueToken) {
        ListOptionsBuilder builder = new ListOptionsBuilder()
                .withLimit((long) properties.pageSize())
                .withContinue(continueToken);
        if (properties.labelSelector() != null && !properties.labelSelector().isBlank()) {
            builder.withLabelSelector(properties.labelSelector());
        }
        return builder.build();
    }

    private ResourceDefinitionContext context(String kind, String plural) {
        return new ResourceDefinitionContext.Builder()
                .withGroup(properties.tekton().group())
                .withVersion(properties.tekton().apiVersion())
                .withKind(kind)
                .withPlural(plural)
                .withNamespaced(true)
                .build();
    }
}
