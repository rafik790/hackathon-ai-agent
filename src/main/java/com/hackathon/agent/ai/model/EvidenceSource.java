package com.hackathon.agent.ai.model;

/**
 * Where a piece of supporting evidence was observed.
 */
public enum EvidenceSource {
    CONTAINER_LOG, CONTAINER_STATUS, POD_STATUS, TASK_RUN, PIPELINE_RUN
}
