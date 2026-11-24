package com.hackathon.agent.evidence.client;

import com.hackathon.agent.config.EvidenceProperties;
import com.hackathon.agent.config.MonitoringProperties;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.RequestConfigBuilder;
import io.fabric8.kubernetes.client.dsl.base.ResourceDefinitionContext;
import io.fabric8.kubernetes.client.http.HttpClient;
import io.fabric8.kubernetes.client.http.HttpResponse;
import io.fabric8.kubernetes.client.utils.URLUtils;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.stereotype.Component;

/**
 * Read-only access to the resources needed for failure evidence: {@code get} on TaskRuns, Pods and Pod logs.
 * <p>
 * Uses a view of the existing Kubernetes client that shares its connection and authentication but has its own
 * request timeout and no automatic retries: retrying happens across cycles with persisted backoff, so a slow
 * API cannot keep the worker busy. Phase 1 requests are not affected.
 */
@Component
@ConditionalOnBooleanProperty(name = "evidence.enabled")
public class KubernetesEvidenceClient {

    private static final Logger log = LoggerFactory.getLogger(KubernetesEvidenceClient.class);

    private final KubernetesClient client;
    private final int requestTimeoutMs;
    private final ResourceDefinitionContext taskRunContext;

    public KubernetesEvidenceClient(KubernetesClient client, MonitoringProperties monitoring,
                                    EvidenceProperties properties) {
        // Not a bean and never closed: closing it would close the connection shared with Phase 1.
        this.client = client.newClient(new RequestConfigBuilder(client.getConfiguration().getRequestConfig())
                        .withRequestTimeout(properties.requestTimeoutMs())
                        .withRequestRetryBackoffLimit(0)
                        .build())
                .adapt(KubernetesClient.class);
        this.requestTimeoutMs = properties.requestTimeoutMs();
        this.taskRunContext = new ResourceDefinitionContext.Builder()
                .withGroup(monitoring.tekton().group())
                .withVersion(monitoring.tekton().apiVersion())
                .withKind("TaskRun")
                .withPlural("taskruns")
                .withNamespaced(true)
                .build();
    }

    /** @return empty when the TaskRun does not exist */
    public Optional<GenericKubernetesResource> getTaskRun(String namespace, String name) {
        return Optional.ofNullable(call("get taskrun " + namespace + "/" + name,
                () -> client.genericKubernetesResources(taskRunContext).inNamespace(namespace).withName(name).get()));
    }

    /** @return empty when the Pod does not exist */
    public Optional<Pod> getPod(String namespace, String name) {
        return Optional.ofNullable(call("get pod " + namespace + "/" + name,
                () -> client.pods().inNamespace(namespace).withName(name).get()));
    }

    /**
     * Opens the last lines of a container log. The caller must close the stream.
     * <p>
     * Fabric8's own log operations first list and watch the Pod to wait for readiness, which needs permissions
     * the agent does not have, so the log endpoint is requested directly through the same HTTP client.
     * {@code limitBytes} is deliberately not used: Kubernetes applies it after positioning on the first tail
     * line, so it would cut off the end of the log, where the failure is reported.
     */
    public InputStream openLog(String namespace, String podName, String container, boolean previous, int tailLines) {
        String request = "get log %s/%s/%s%s".formatted(namespace, podName, container, previous ? " (previous)" : "");
        return call(request, () -> {
            HttpClient http = client.getHttpClient();
            // Joined rather than resolved, because the API server URL may have a base path.
            URI uri = URI.create(URLUtils.join(client.getMasterUrl().toString(),
                    "api/v1/namespaces/%s/pods/%s/log?container=%s&tailLines=%d%s".formatted(encode(namespace),
                            encode(podName), encode(container), tailLines, previous ? "&previous=true" : "")));
            CompletableFuture<HttpResponse<InputStream>> pending = http.sendAsync(
                    http.newHttpRequestBuilder().uri(uri).timeout(requestTimeoutMs, TimeUnit.MILLISECONDS).build(),
                    InputStream.class);
            HttpResponse<InputStream> response = await(pending);
            if (!response.isSuccessful()) {
                close(response);
                throw new KubernetesClientException(request, response.code(), null);
            }
            return response.body();
        });
    }

    private HttpResponse<InputStream> await(CompletableFuture<HttpResponse<InputStream>> pending) {
        try {
            return pending.get(requestTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (ExecutionException e) {
            throw new KubernetesClientException("Log request failed", e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            pending.thenAccept(KubernetesEvidenceClient::close);
            throw new KubernetesClientException("Log request interrupted", e);
        } catch (TimeoutException e) {
            // A response that still arrives must not keep its connection.
            pending.thenAccept(KubernetesEvidenceClient::close);
            throw new KubernetesClientException("Log request timed out", e);
        }
    }

    private static void close(HttpResponse<InputStream> response) {
        try {
            if (response.body() != null) {
                response.body().close();
            }
        } catch (IOException e) {
            log.debug("Closing a log response failed", e);
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static <T> T call(String request, Supplier<T> supplier) {
        try {
            return supplier.get();
        } catch (RuntimeException e) {
            throw EvidenceApiException.of(request, e);
        }
    }
}
