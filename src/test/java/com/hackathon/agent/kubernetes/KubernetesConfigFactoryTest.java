package com.hackathon.agent.kubernetes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hackathon.agent.config.KubernetesProperties;
import com.hackathon.agent.enums.KubernetesAuthMode;
import io.fabric8.kubernetes.client.Config;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class KubernetesConfigFactoryTest {

    private static final String KUBECONFIG = """
            apiVersion: v1
            kind: Config
            clusters:
            - name: local
              cluster:
                server: https://127.0.0.1:6443
                insecure-skip-tls-verify: true
            - name: other
              cluster:
                server: https://10.0.0.1:6443
            users:
            - name: dev
              user:
                token: placeholder-token
            contexts:
            - name: local
              context: {cluster: local, user: dev, namespace: ci}
            - name: other
              context: {cluster: other, user: dev}
            current-context: local
            """;

    @TempDir
    Path tempDir;

    @Test
    void kubeconfigModeUsesConfiguredFileAndTimeouts() throws IOException {
        Path kubeconfig = Files.writeString(tempDir.resolve("config"), KUBECONFIG);

        Config config = new KubernetesConfigFactory(
                new KubernetesProperties(KubernetesAuthMode.KUBECONFIG, kubeconfig.toString(), null, 1234, 5678)).create();

        assertThat(config.getMasterUrl()).startsWith("https://127.0.0.1:6443");
        assertThat(config.getConnectionTimeout()).isEqualTo(1234);
        assertThat(config.getRequestTimeout()).isEqualTo(5678);
    }

    @Test
    void kubeconfigModeHonoursContext() throws IOException {
        Path kubeconfig = Files.writeString(tempDir.resolve("config"), KUBECONFIG);

        Config config = new KubernetesConfigFactory(
                new KubernetesProperties(KubernetesAuthMode.KUBECONFIG, kubeconfig.toString(), "other", 1000, 1000)).create();

        assertThat(config.getMasterUrl()).startsWith("https://10.0.0.1:6443");
    }

    @Test
    void kubeconfigModeFailsFastWhenFileIsMissing() {
        KubernetesProperties properties = new KubernetesProperties(KubernetesAuthMode.KUBECONFIG,
                tempDir.resolve("missing").toString(), null, 1000, 1000);

        assertThatThrownBy(() -> new KubernetesConfigFactory(properties).create())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Kubeconfig file not found");
    }

    @Test
    void inClusterModeRequiresServiceAccountToken() {
        KubernetesProperties properties = new KubernetesProperties(KubernetesAuthMode.IN_CLUSTER, null, null, 1000, 1000);

        assertThatThrownBy(() -> new KubernetesConfigFactory(properties, tempDir.resolve("token")).create())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ServiceAccount token");
    }
}
