package com.hackathon.agent.config;

import com.hackathon.agent.enums.KubernetesAuthMode;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Kubernetes API connection settings ({@code kubernetes.*}). Credentials are never configured here:
 * they come from the kubeconfig file or the mounted ServiceAccount token.
 *
 * @param authMode            AUTO, KUBECONFIG or IN_CLUSTER
 * @param kubeconfigPath      optional kubeconfig path (KUBECONFIG mode); defaults to $KUBECONFIG or ~/.kube/config
 * @param context             optional kubeconfig context name; defaults to the current context
 * @param connectionTimeoutMs TCP connection timeout to the API server
 * @param requestTimeoutMs    timeout for a single API request
 */
@Validated
@ConfigurationProperties(prefix = "kubernetes")
public record KubernetesProperties(
        @NotNull KubernetesAuthMode authMode,
        String kubeconfigPath,
        String context,
        @Min(100) int connectionTimeoutMs,
        @Min(100) int requestTimeoutMs) {
}
