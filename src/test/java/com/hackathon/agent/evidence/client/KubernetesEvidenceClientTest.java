package com.hackathon.agent.evidence.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hackathon.agent.config.EvidenceProperties;
import com.hackathon.agent.config.MonitoringProperties;
import com.hackathon.agent.evidence.client.EvidenceApiException.Category;
import com.hackathon.agent.support.EvidenceFixtures;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.ConfigBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer;
import io.fabric8.mockwebserver.http.RecordedRequest;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Runs against Fabric8's in-memory API server (no real cluster).
 */
@EnableKubernetesMockClient
class KubernetesEvidenceClientTest {

    private static final int TIMEOUT_MS = 30000;
    private static final String POD = "/api/v1/namespaces/ci/pods/build-pod";
    private static final String TASK_RUN = "/apis/tekton.dev/v1/namespaces/ci/taskruns/build";

    KubernetesMockServer server;
    KubernetesClient client;

    @Test
    void readsTaskRunAndPodByName() throws InterruptedException {
        server.expect().get().withPath(TASK_RUN).andReturn(200, EvidenceFixtures.failedTaskRun("build", "uid-1")).once();
        server.expect().get().withPath(POD).andReturn(200, EvidenceFixtures.failedPod("build-pod", "uid-1")).once();

        assertThat(evidenceClient().getTaskRun("ci", "build").orElseThrow().getMetadata().getUid()).isEqualTo("uid-1");
        assertThat(evidenceClient().getPod("ci", "build-pod").orElseThrow().getStatus().getPhase()).isEqualTo("Failed");
        assertThat(requests()).containsExactly("GET " + TASK_RUN, "GET " + POD);
    }

    @Test
    void missingResourcesAreEmpty() {
        assertThat(evidenceClient().getTaskRun("ci", "missing")).isEmpty();
        assertThat(evidenceClient().getPod("ci", "missing")).isEmpty();
    }

    // One request per test: the mock server may close a connection after a log response, and a second request could
    // then fail on the closed connection.
    @Test
    void logRequestsOnlyNeedGetOnPodLogs() throws Exception {
        server.expect().get().withPath(POD + "/log?container=step-test&tailLines=500")
                .andReturn(200, "current log").once();

        assertThat(read(evidenceClient().openLog("ci", "build-pod", "step-test", false, 500))).isEqualTo("current log");
        // No watch or list, and no limitBytes, which would cut off the end of the log.
        assertThat(requests()).containsExactly("GET " + POD + "/log?container=step-test&tailLines=500");
    }

    @Test
    void logOfThePreviousContainerInstanceIsRequestedWithPrevious() throws Exception {
        server.expect().get().withPath(POD + "/log?container=step-test&tailLines=500&previous=true")
                .andReturn(200, "previous log").once();

        assertThat(read(evidenceClient().openLog("ci", "build-pod", "step-test", true, 500))).isEqualTo("previous log");
        assertThat(requests()).containsExactly("GET " + POD + "/log?container=step-test&tailLines=500&previous=true");
    }

    @Test
    void closingAStreamEarlyReleasesItsConnection() throws IOException {
        server.expect().get().withPath(POD + "/log?container=step-test&tailLines=500")
                .andReturn(200, ("line of output" + System.lineSeparator()).repeat(50_000)).always();
        server.expect().get().withPath(POD).andReturn(200, EvidenceFixtures.failedPod("build-pod", "uid-1")).always();
        KubernetesEvidenceClient evidenceClient = evidenceClient();

        for (int i = 0; i < 50; i++) {
            try (InputStream stream = evidenceClient.openLog("ci", "build-pod", "step-test", false, 500)) {
                assertThat(stream.read()).isEqualTo('l');
            }
        }

        assertThat(evidenceClient.getPod("ci", "build-pod")).isPresent();
    }

    @ParameterizedTest
    @CsvSource({"400, PERMANENT", "401, FORBIDDEN", "403, FORBIDDEN", "404, NOT_FOUND", "429, TRANSIENT",
            "500, TRANSIENT", "503, TRANSIENT"})
    void classifiesLogErrorsWithoutExposingTheResponse(int code, Category expected) throws InterruptedException {
        server.expect().get().withPath(POD + "/log?container=step-test&tailLines=500")
                .andReturn(code, "password=in-response-body").always();

        assertThatThrownBy(() -> evidenceClient().openLog("ci", "build-pod", "step-test", false, 500))
                .isInstanceOfSatisfying(EvidenceApiException.class, e -> assertThat(e.category()).isEqualTo(expected))
                .hasMessage("get log ci/build-pod/step-test failed: HTTP " + code);
        assertThat(requests()).hasSize(1);
    }

    @Test
    void logRequestsCarryTheConfiguredToken() throws Exception {
        server.expect().get().withPath(POD + "/log?container=step-test&tailLines=500").andReturn(200, "log").once();
        Config config = new ConfigBuilder(client.getConfiguration()).withOauthToken("placeholder-token").build();

        try (KubernetesClient authenticated = new KubernetesClientBuilder().withConfig(config).build()) {
            read(evidenceClient(authenticated, TIMEOUT_MS).openLog("ci", "build-pod", "step-test", false, 500));
        }

        assertThat(server.takeRequest(1, TimeUnit.SECONDS).getHeader("Authorization"))
                .isEqualTo("Bearer placeholder-token");
    }

    @Test
    void unresponsiveLogRequestTimesOut() {
        server.expect().get().withPath(POD + "/log?container=step-test&tailLines=500")
                .andReply(200, request -> {
                    LockSupport.parkNanos(TimeUnit.SECONDS.toNanos(4));
                    return "late";
                }).always();

        assertThatThrownBy(() -> evidenceClient(client, 1500).openLog("ci", "build-pod", "step-test", false, 500))
                .isInstanceOfSatisfying(EvidenceApiException.class,
                        e -> assertThat(e.category()).isEqualTo(Category.TRANSIENT));
    }

    @Test
    void evidenceRequestsAreNotRetriedWhilePhase1RequestsStillAre() throws InterruptedException {
        server.expect().get().withPath(POD).andReturn(503, "").always();

        assertThatThrownBy(() -> evidenceClient().getPod("ci", "build-pod")).isInstanceOf(EvidenceApiException.class);
        assertThat(requests()).hasSize(1);

        int configuredRetries = client.getConfiguration().getRequestRetryBackoffLimit();
        server.expect().get().withPath("/api/v1/namespaces/ci/pods/other").andReturn(503, "").times(1);
        server.expect().get().withPath("/api/v1/namespaces/ci/pods/other")
                .andReturn(200, EvidenceFixtures.failedPod("other", "x")).once();
        assertThat(configuredRetries).isPositive();
        assertThat(client.pods().inNamespace("ci").withName("other").get()).isNotNull();
        assertThat(client.getConfiguration().getRequestTimeout())
                .isNotEqualTo(TIMEOUT_MS);
    }

    private KubernetesEvidenceClient evidenceClient() {
        return evidenceClient(client, TIMEOUT_MS);
    }

    private static KubernetesEvidenceClient evidenceClient(KubernetesClient kubernetes, int timeoutMs) {
        return new KubernetesEvidenceClient(kubernetes,
                new MonitoringProperties(true, 15000, 0, List.of("ci"), null, 100, null),
                new EvidenceProperties(true, 30000, 0, 24, 10, 2, 500, 65536, 262144, 14, 5, 60, timeoutMs));
    }

    private List<String> requests() throws InterruptedException {
        List<String> requests = new ArrayList<>();
        RecordedRequest request;
        while ((request = server.takeRequest(200, TimeUnit.MILLISECONDS)) != null) {
            requests.add(request.getMethod() + " " + request.getPath());
        }
        return requests;
    }

    private static String read(InputStream stream) throws IOException {
        try (stream) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
