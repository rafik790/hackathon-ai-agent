package com.hackathon.agent.kubernetes;

import com.hackathon.agent.config.KubernetesProperties;
import io.fabric8.kubernetes.client.Config;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Builds the Fabric8 {@link Config} for the configured authentication mode.
 * <p>
 * No credentials are read from application configuration: KUBECONFIG mode uses the kubeconfig file,
 * IN_CLUSTER mode uses the ServiceAccount token mounted by Kubernetes and AUTO lets Fabric8 decide
 * (kubeconfig when present, otherwise the in-cluster ServiceAccount).
 */
public class KubernetesConfigFactory {

    static final String SERVICE_ACCOUNT_TOKEN_PATH = "/var/run/secrets/kubernetes.io/serviceaccount/token";

    private static final Logger log = LoggerFactory.getLogger(KubernetesConfigFactory.class);

    private final KubernetesProperties properties;
    private final Path serviceAccountTokenPath;

    public KubernetesConfigFactory(KubernetesProperties properties) {
        this(properties, Path.of(SERVICE_ACCOUNT_TOKEN_PATH));
    }

    KubernetesConfigFactory(KubernetesProperties properties, Path serviceAccountTokenPath) {
        this.properties = properties;
        this.serviceAccountTokenPath = serviceAccountTokenPath;
    }

    public Config create() {
        Config config = switch (properties.authMode()) {
            case KUBECONFIG -> fromKubeconfig();
            case IN_CLUSTER -> inCluster();
            case AUTO -> Config.autoConfigure(blankToNull(properties.context()));
        };
        config.setConnectionTimeout(properties.connectionTimeoutMs());
        config.setRequestTimeout(properties.requestTimeoutMs());
        log.info("Kubernetes client configured: authMode={}, apiServer={}, connectionTimeoutMs={}, requestTimeoutMs={}",
                properties.authMode(), config.getMasterUrl(), properties.connectionTimeoutMs(),
                properties.requestTimeoutMs());
        return config;
    }

    private Config fromKubeconfig() {
        File kubeconfig = resolveKubeconfigFile();
        if (!kubeconfig.isFile()) {
            throw new IllegalStateException("Kubeconfig file not found: " + kubeconfig.getAbsolutePath()
                    + ". Set KUBERNETES_KUBECONFIG_PATH or KUBECONFIG, or use KUBERNETES_AUTH_MODE=AUTO");
        }
        return Config.fromKubeconfig(blankToNull(properties.context()), kubeconfig);
    }

    private Config inCluster() {
        if (!Files.isReadable(serviceAccountTokenPath)) {
            throw new IllegalStateException("IN_CLUSTER auth mode selected but no ServiceAccount token is mounted at "
                    + serviceAccountTokenPath + ". Is the application running inside a Kubernetes pod?");
        }
        // Prevent a stray kubeconfig inside the container from taking precedence over the ServiceAccount.
        System.setProperty(Config.KUBERNETES_AUTH_TRYKUBECONFIG_SYSTEM_PROPERTY, "false");
        return Config.autoConfigure(null);
    }

    private File resolveKubeconfigFile() {
        if (properties.kubeconfigPath() != null && !properties.kubeconfigPath().isBlank()) {
            return new File(properties.kubeconfigPath());
        }
        String fromEnv = System.getenv("KUBECONFIG");
        if (fromEnv != null && !fromEnv.isBlank()) {
            // KUBECONFIG may contain several paths; the first one is used.
            return new File(fromEnv.split(File.pathSeparator)[0]);
        }
        return Path.of(System.getProperty("user.home"), ".kube", "config").toFile();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
