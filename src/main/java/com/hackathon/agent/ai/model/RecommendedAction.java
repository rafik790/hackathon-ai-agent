package com.hackathon.agent.ai.model;

/**
 * A recommendation for people; the application never executes it.
 */
public record RecommendedAction(RecommendationPriority priority, String action, String reason) {
}
