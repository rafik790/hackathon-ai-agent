package com.hackathon.agent.kubernetes;

/**
 * Raised when the Kubernetes API cannot be read (connectivity, timeout, RBAC, missing CRDs).
 */
public class KubernetesAccessException extends RuntimeException {

    public KubernetesAccessException(String message, Throwable cause) {
        super(message, cause);
    }
}
