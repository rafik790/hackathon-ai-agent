package com.hackathon.agent.evidence.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hackathon.agent.config.EvidenceProperties;
import com.hackathon.agent.evidence.client.EvidenceApiException;
import com.hackathon.agent.evidence.client.KubernetesEvidenceClient;
import com.hackathon.agent.evidence.model.EvidenceCounters;
import com.hackathon.agent.evidence.model.LogSnapshot;
import com.hackathon.agent.evidence.model.LogStatus;
import com.hackathon.agent.evidence.sanitizer.LogSanitizer;
import com.hackathon.agent.evidence.service.ContainerLogCollector.LogRequest;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ContainerLogCollectorTest {

    // 200 ms request timeout, so a stalled log is given up after 600 ms.
    private final EvidenceProperties properties =
            new EvidenceProperties(true, 30000, 0, 24, 10, 2, 500, 65536, 262144, 14, 5, 60, 200);
    private final KubernetesEvidenceClient client = mock(KubernetesEvidenceClient.class);
    private final EvidenceCounters counters = new EvidenceCounters();
    private final ContainerLogCollector collector =
            new ContainerLogCollector(client, new LogSanitizer(), properties, Clock.systemUTC());

    @AfterEach
    void stop() {
        collector.stop();
    }

    @Test
    void collectsAndSanitizesLogsInRequestOrder() {
        logOf("step-a", false, "failed with password=hunter2");
        logOf("step-b", true, "previous");

        List<LogSnapshot> logs = collect(new LogRequest("step-a", false), new LogRequest("step-b", true));

        assertThat(logs).extracting(LogSnapshot::containerName).containsExactly("step-a", "step-b");
        assertThat(logs.get(0).log().content()).isEqualTo("failed with password=[REDACTED]");
        assertThat(logs.get(1).previous()).isTrue();
        assertThat(logs.get(1).log().content()).isEqualTo("previous");
        assertThat(counters.logsFetched.get()).isEqualTo(2);
        verify(client).openLog("ci", "pod", "step-a", false, 500);
    }

    @Test
    void keepsOnlyTheLast64KbOfHugeLogs() {
        logOf("step-a", false, "first line\n" + "line of output\n".repeat(200_000) + "final error");

        LogSnapshot log = collect(new LogRequest("step-a", false)).getFirst();

        assertThat(log.log().storedBytes()).isLessThanOrEqualTo(65536).isGreaterThan(65000);
        assertThat(log.log().truncated()).isTrue();
        assertThat(log.log().content()).endsWith("final error").doesNotContain("first line");
    }

    @Test
    void receivingAllRequestedLinesCountsAsTruncated() {
        logOf("step-a", false, "x\n".repeat(500));
        logOf("step-b", false, "x\n".repeat(499));

        List<LogSnapshot> logs = collect(new LogRequest("step-a", false), new LogRequest("step-b", false));

        assertThat(logs.get(0).log().truncated()).isTrue();
        assertThat(logs.get(1).log().truncated()).isFalse();
    }

    @Test
    void emptyAndRemovedLogsHaveTheirOwnStatus() {
        logOf("step-a", false, "");
        logOf("step-b", false, "unable to retrieve container logs for containerd://0123abc");
        logOf("step-c", false, "unable to retrieve container logs for x\nbut this is real output");

        assertThat(collect(new LogRequest("step-a", false), new LogRequest("step-b", false),
                new LogRequest("step-c", false))).extracting(LogSnapshot::status)
                .containsExactly(LogStatus.EMPTY, LogStatus.EXPIRED, LogStatus.COLLECTED);
    }

    @ParameterizedTest
    @CsvSource({"FORBIDDEN, FORBIDDEN, 1", "NOT_FOUND, NOT_AVAILABLE, 0", "PERMANENT, NOT_AVAILABLE, 0",
            "TRANSIENT, ERROR, 0"})
    void apiErrorsAreIsolatedPerLog(EvidenceApiException.Category category, LogStatus expected, int forbidden) {
        when(client.openLog(any(), any(), eq("step-a"), anyBoolean(), anyInt()))
                .thenThrow(new EvidenceApiException(category, "get log failed", null));
        logOf("step-b", false, "ok");

        List<LogSnapshot> logs = collect(new LogRequest("step-a", false), new LogRequest("step-b", false));

        assertThat(logs).extracting(LogSnapshot::status).containsExactly(expected, LogStatus.COLLECTED);
        assertThat(counters.failedApiCalls.get()).isEqualTo(1);
        assertThat(counters.forbiddenApiCalls.get()).isEqualTo(forbidden);
    }

    @Test
    void connectionLostWhileReadingIsARetryableError() {
        when(client.openLog(any(), any(), eq("step-a"), anyBoolean(), anyInt())).thenReturn(new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("connection reset");
            }
        });

        LogSnapshot log = collect(new LogRequest("step-a", false)).getFirst();

        assertThat(log.status()).isEqualTo(LogStatus.ERROR);
        assertThat(log.log()).isNull();
    }

    @Test
    void stalledLogIsGivenUpAndItsStreamClosed() {
        CountDownLatch closed = new CountDownLatch(1);
        when(client.openLog(any(), any(), eq("step-a"), anyBoolean(), anyInt())).thenReturn(new InputStream() {
            @Override
            public int read() throws IOException {
                // Ignores interrupts, like a blocked socket: only closing the stream ends it.
                boolean waiting = true;
                while (waiting) {
                    try {
                        closed.await();
                        waiting = false;
                    } catch (InterruptedException ignored) {
                        // keep waiting
                    }
                }
                throw new IOException("closed");
            }

            @Override
            public void close() {
                closed.countDown();
            }
        });
        logOf("step-b", false, "ok");

        List<LogSnapshot> logs = collect(new LogRequest("step-a", false), new LogRequest("step-b", false));

        assertThat(logs).extracting(LogSnapshot::status).containsExactly(LogStatus.ERROR, LogStatus.COLLECTED);
        assertThat(closed.getCount()).isZero();
    }

    @Test
    void neverRunsMoreThanTwoRequestsAtOnce() {
        AtomicInteger active = new AtomicInteger();
        AtomicInteger max = new AtomicInteger();
        AtomicBoolean overlapped = new AtomicBoolean();
        when(client.openLog(anyString(), anyString(), anyString(), anyBoolean(), anyInt())).thenAnswer(call -> {
            int now = active.incrementAndGet();
            max.accumulateAndGet(now, Math::max);
            overlapped.compareAndSet(false, now > 1);
            Thread.sleep(40);
            active.decrementAndGet();
            return new ByteArrayInputStream("x".getBytes(StandardCharsets.UTF_8));
        });

        collect(new LogRequest("a", false), new LogRequest("b", false), new LogRequest("c", false),
                new LogRequest("a", true), new LogRequest("b", true), new LogRequest("c", true));

        assertThat(max.get()).isEqualTo(2);
        assertThat(overlapped).isTrue();
    }

    private void logOf(String container, boolean previous, String content) {
        when(client.openLog(any(), any(), eq(container), eq(previous), anyInt()))
                .thenReturn(new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)));
    }

    private List<LogSnapshot> collect(LogRequest... requests) {
        return collector.collect("ci", "pod", List.of(requests), counters);
    }
}
