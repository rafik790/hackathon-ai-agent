package com.hackathon.agent.evidence.client;

import io.fabric8.kubernetes.client.KubernetesClientException;

/**
 * A failed Kubernetes request, classified by how the evidence worker must react.
 */
public class EvidenceApiException extends RuntimeException {

    public enum Category {
        /** 401/403: retried a limited number of times because RBAC may be granted later. */
        FORBIDDEN,
        /** 404: the resource or log does not exist. */
        NOT_FOUND,
        /** Other 4xx: repeating the request cannot help. */
        PERMANENT,
        /** 408, 429, 5xx, timeouts and I/O errors. */
        TRANSIENT
    }

    private final Category category;

    public EvidenceApiException(Category category, String message, Throwable cause) {
        super(message, cause);
        this.category = category;
    }

    /** The message only names the request and the HTTP code; response bodies may contain anything. */
    static EvidenceApiException of(String request, RuntimeException cause) {
        int code = cause instanceof KubernetesClientException e ? e.getCode() : 0;
        String outcome = code > 0 ? "HTTP " + code : cause.getClass().getSimpleName();
        return new EvidenceApiException(categorize(code), request + " failed: " + outcome, cause);
    }

    static Category categorize(int code) {
        if (code == 401 || code == 403) {
            return Category.FORBIDDEN;
        }
        if (code == 404) {
            return Category.NOT_FOUND;
        }
        if (code >= 400 && code < 500 && code != 408 && code != 429) {
            return Category.PERMANENT;
        }
        return Category.TRANSIENT;
    }

    public Category category() {
        return category;
    }
}
