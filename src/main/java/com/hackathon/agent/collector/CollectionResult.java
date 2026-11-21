package com.hackathon.agent.collector;

import com.hackathon.agent.service.UpsertResult;

/**
 * Result of one collector run for one namespace.
 *
 * @param discovered resources returned by the Kubernetes API
 * @param skipped    resources ignored because they lacked identifying metadata
 */
public record CollectionResult(String resourceKind, String namespace, int discovered, int skipped,
                               UpsertResult upsert) {
}
