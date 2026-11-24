package com.hackathon.agent.ai.model;

/**
 * What kind of problem made the execution fail.
 */
public enum FailureCategory {
    SOURCE_CHECKOUT,
    DEPENDENCY_RESOLUTION,
    COMPILATION,
    TEST_FAILURE,
    CODE_QUALITY,
    SCRIPT_ERROR,
    IMAGE_BUILD,
    IMAGE_PULL,
    REGISTRY_AUTHENTICATION,
    PERMISSION_DENIED,
    CONFIGURATION,
    NETWORK,
    RESOURCE_EXHAUSTION,
    TIMEOUT,
    CANCELLED,
    DEPLOYMENT,
    TEKTON,
    KUBERNETES_INFRASTRUCTURE,
    EXTERNAL_SERVICE,
    UNKNOWN
}
