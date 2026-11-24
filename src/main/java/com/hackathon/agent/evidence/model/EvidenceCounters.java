package com.hackathon.agent.evidence.model;

import com.hackathon.agent.evidence.client.EvidenceApiException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Mutable counters of a running cycle; log requests update them from several threads.
 */
public class EvidenceCounters {

    public final AtomicInteger taskRunsExamined = new AtomicInteger();
    public final AtomicInteger evidenceCreated = new AtomicInteger();
    public final AtomicInteger evidenceUpdated = new AtomicInteger();
    public final AtomicInteger logsFetched = new AtomicInteger();
    public final AtomicInteger logsSkipped = new AtomicInteger();
    public final AtomicInteger failedApiCalls = new AtomicInteger();
    public final AtomicInteger forbiddenApiCalls = new AtomicInteger();
    public final AtomicInteger taskRunErrors = new AtomicInteger();

    public void apiFailure(EvidenceApiException.Category category) {
        failedApiCalls.incrementAndGet();
        if (category == EvidenceApiException.Category.FORBIDDEN) {
            forbiddenApiCalls.incrementAndGet();
        }
    }
}
