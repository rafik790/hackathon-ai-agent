package com.hackathon.agent.ai.model;

/**
 * Readable level of the numeric confidence score.
 */
public enum ConfidenceLevel {
    LOW, MEDIUM, HIGH;

    public static ConfidenceLevel of(double confidence) {
        if (confidence >= 0.8) {
            return HIGH;
        }
        return confidence >= 0.5 ? MEDIUM : LOW;
    }
}
