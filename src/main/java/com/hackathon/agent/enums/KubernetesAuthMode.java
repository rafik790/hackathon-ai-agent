package com.hackathon.agent.enums;

/**
 * How the application authenticates against the Kubernetes API server.
 */
public enum KubernetesAuthMode {
    /** Let Fabric8 detect: kubeconfig when present, otherwise the in-cluster ServiceAccount. */
    AUTO,
    /** Use a kubeconfig file (local development). */
    KUBECONFIG,
    /** Use the mounted ServiceAccount token (running inside the cluster). */
    IN_CLUSTER
}
