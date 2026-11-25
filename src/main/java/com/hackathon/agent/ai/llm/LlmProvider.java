package com.hackathon.agent.ai.llm;

/**
 * A large language model. Implementations convert between the common contracts and their own API, are thread-safe
 * and never retry: retries, backoff and rate limiting belong to the caller.
 */
public interface LlmProvider {

    /** Provider name as used in the configuration. */
    String name();

    /** Whether invocations cost money and therefore count towards the daily quota. */
    default boolean consumesQuota() {
        return true;
    }

    /** Configured model. */
    String model();

    /**
     * @throws LlmProviderException if no complete answer was produced
     */
    LlmResponse generate(LlmRequest request);
}
