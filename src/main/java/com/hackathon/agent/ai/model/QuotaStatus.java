package com.hackathon.agent.ai.model;

import java.time.Instant;

/**
 * @param limited     false for providers whose invocations are free
 * @param used        invocations within the last 24 hours
 * @param limit       invocations allowed within 24 hours
 * @param availableAt when the next invocation becomes possible; null unless the quota is exhausted
 */
public record QuotaStatus(boolean limited, int used, int limit, Instant availableAt) {

    public static final QuotaStatus UNLIMITED = new QuotaStatus(false, 0, Integer.MAX_VALUE, null);

    public int remaining() {
        return limited ? Math.max(0, limit - used) : Integer.MAX_VALUE;
    }

    public boolean isExhausted() {
        return remaining() == 0;
    }
}
