package com.hackathon.agent.evidence.service;

import com.hackathon.agent.config.EvidenceProperties;
import com.hackathon.agent.evidence.client.EvidenceApiException;
import com.hackathon.agent.evidence.client.KubernetesEvidenceClient;
import com.hackathon.agent.evidence.model.EvidenceCounters;
import com.hackathon.agent.evidence.model.LogSnapshot;
import com.hackathon.agent.evidence.model.LogStatus;
import com.hackathon.agent.evidence.model.SanitizedLog;
import com.hackathon.agent.evidence.sanitizer.LogSanitizer;
import com.hackathon.agent.evidence.sanitizer.TailBuffer;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.stereotype.Component;

/**
 * Fetches container logs with bounded concurrency, memory and time. A failed log never fails the others.
 */
@Component
@ConditionalOnBooleanProperty(name = "evidence.enabled")
public class ContainerLogCollector {

    private static final Logger log = LoggerFactory.getLogger(ContainerLogCollector.class);

    /** Answer of the kubelet, with HTTP 200, when the log file of a container has been removed. */
    private static final String LOG_REMOVED = "unable to retrieve container logs for ";
    /** The request timeout covers the response start; reading the body gets a multiple of it. */
    private static final int READ_TIMEOUT_FACTOR = 3;

    /** One log stream to fetch. */
    public record LogRequest(String container, boolean previous) {
    }

    private final KubernetesEvidenceClient client;
    private final LogSanitizer sanitizer;
    private final EvidenceProperties properties;
    private final Clock clock;
    // Deliberately not a Spring bean: an executor bean would replace the one used for Phase 1 scheduling.
    private final ExecutorService executor;

    public ContainerLogCollector(KubernetesEvidenceClient client, LogSanitizer sanitizer,
                                 EvidenceProperties properties, Clock clock) {
        this.client = client;
        this.sanitizer = sanitizer;
        this.properties = properties;
        this.clock = clock;
        this.executor = Executors.newFixedThreadPool(properties.maxConcurrentLogRequests(),
                Thread.ofVirtual().name("evidence-log-", 0).factory());
    }

    /** @return one result per request, in request order */
    public List<LogSnapshot> collect(String namespace, String podName, List<LogRequest> requests,
                                     EvidenceCounters counters) {
        List<AtomicReference<InputStream>> streams = new ArrayList<>();
        List<Future<LogSnapshot>> futures = new ArrayList<>();
        for (LogRequest request : requests) {
            AtomicReference<InputStream> stream = new AtomicReference<>();
            streams.add(stream);
            futures.add(executor.submit(() -> fetch(namespace, podName, request, stream, counters)));
        }
        List<LogSnapshot> results = new ArrayList<>();
        for (int i = 0; i < requests.size(); i++) {
            results.add(await(requests.get(i), futures.get(i), streams.get(i), counters));
        }
        return results;
    }

    private LogSnapshot await(LogRequest request, Future<LogSnapshot> future, AtomicReference<InputStream> stream,
                              EvidenceCounters counters) {
        try {
            return future.get((long) properties.requestTimeoutMs() * READ_TIMEOUT_FACTOR, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            log.warn("Log request timed out: container={} previous={}", request.container(), request.previous());
            counters.apiFailure(EvidenceApiException.Category.TRANSIENT);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException e) {
            log.warn("Log request failed unexpectedly: container={} previous={}", request.container(),
                    request.previous(), e.getCause());
            counters.apiFailure(EvidenceApiException.Category.TRANSIENT);
        }
        future.cancel(true);
        close(stream.get());
        return result(request, LogStatus.ERROR, null);
    }

    private LogSnapshot fetch(String namespace, String podName, LogRequest request,
                              AtomicReference<InputStream> streamHolder, EvidenceCounters counters) {
        TailBuffer tail = new TailBuffer(properties.maxContainerLogBytes() + LogSanitizer.CONTEXT_BYTES);
        try (InputStream stream = client.openLog(namespace, podName, request.container(), request.previous(),
                properties.maxLogLines())) {
            streamHolder.set(stream);
            tail.readFrom(stream);
        } catch (EvidenceApiException e) {
            counters.apiFailure(e.category());
            log.warn("{}", e.getMessage());
            return result(request, switch (e.category()) {
                case FORBIDDEN -> LogStatus.FORBIDDEN;
                case NOT_FOUND, PERMANENT -> LogStatus.NOT_AVAILABLE;
                case TRANSIENT -> LogStatus.ERROR;
            }, null);
        } catch (IOException e) {
            counters.apiFailure(EvidenceApiException.Category.TRANSIENT);
            log.warn("Reading log of container={} failed: {}", request.container(), e.getClass().getSimpleName());
            return result(request, LogStatus.ERROR, null);
        }

        SanitizedLog sanitized = sanitizer.sanitizeLog(tail.toByteArray(), tail.overflowed(),
                properties.maxContainerLogBytes());
        if (sanitized.content().isEmpty()) {
            counters.logsFetched.incrementAndGet();
            return result(request, LogStatus.EMPTY, null);
        }
        if (sanitized.content().startsWith(LOG_REMOVED) && sanitized.content().strip().lines().count() == 1) {
            return result(request, LogStatus.EXPIRED, null);
        }
        counters.logsFetched.incrementAndGet();
        // Receiving as many lines as requested means there may be earlier ones.
        return result(request, LogStatus.COLLECTED,
                tail.lineBreaks() >= properties.maxLogLines() ? sanitized.asTruncated() : sanitized);
    }

    private LogSnapshot result(LogRequest request, LogStatus status, SanitizedLog sanitized) {
        return new LogSnapshot(request.container(), request.previous(), status, sanitized, clock.instant());
    }

    private static void close(InputStream stream) {
        if (stream != null) {
            try {
                stream.close();
            } catch (IOException e) {
                log.debug("Closing a log stream failed", e);
            }
        }
    }

    @PreDestroy
    void stop() {
        executor.shutdownNow();
    }
}
