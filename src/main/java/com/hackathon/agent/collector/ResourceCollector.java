package com.hackathon.agent.collector;

/**
 * A collector for one kind of Kubernetes/Tekton resource. New resource types (e.g. Pods, Events)
 * can be monitored by adding another implementation; the collection cycle picks up all beans
 * in {@link org.springframework.core.annotation.Order} order.
 */
public interface ResourceCollector {

    /** Resource kind handled by this collector, used in logs and the collection report. */
    String resourceKind();

    /**
     * Reads all resources of this kind from the namespace and persists them.
     *
     * @throws com.hackathon.agent.kubernetes.KubernetesAccessException when the Kubernetes API fails
     * @throws org.springframework.dao.DataAccessException when persistence fails
     */
    CollectionResult collect(String namespace);
}
