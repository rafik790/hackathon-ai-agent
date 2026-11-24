package com.hackathon.agent.ai.model;

/**
 * @param containerName container the observation belongs to, or null
 */
public record SupportingEvidence(EvidenceSource source, String containerName, String observation) {
}
