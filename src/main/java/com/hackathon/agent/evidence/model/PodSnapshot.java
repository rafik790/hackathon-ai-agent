package com.hackathon.agent.evidence.model;

import java.util.List;

/**
 * Pod details relevant for a failure.
 */
public record PodSnapshot(
        String name,
        String uid,
        String phase,
        String reason,
        String message,
        List<ContainerSnapshot> containers) {
}
