package com.hackathon.agent.ai.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hackathon.agent.ai.context.FailureContextBuilder;
import com.hackathon.agent.ai.llm.LlmProvider;
import com.hackathon.agent.ai.llm.LlmProviderException;
import com.hackathon.agent.ai.llm.LlmProviderException.Category;
import com.hackathon.agent.ai.llm.LlmUsage;
import com.hackathon.agent.ai.llm.mock.MockLlmProvider;
import com.hackathon.agent.ai.model.AnalysisClaim;
import com.hackathon.agent.ai.model.AnalysisCycleReport;
import com.hackathon.agent.ai.model.AnalysisStatus;
import com.hackathon.agent.ai.model.QuotaStatus;
import com.hackathon.agent.ai.parser.AnalysisEvidenceValidator;
import com.hackathon.agent.ai.parser.FailureAnalysisParser;
import com.hackathon.agent.ai.prompt.FailureAnalysisPromptBuilder;
import com.hackathon.agent.evidence.sanitizer.LogSanitizer;
import com.hackathon.agent.support.AnalysisFixtures;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;

class FailureAnalysisServiceTest {

    private final AnalysisPersistenceService persistence = mock(AnalysisPersistenceService.class);
    private final AiQuotaService quota = mock(AiQuotaService.class);
    private final FailureContextBuilder contextBuilder = mock(FailureContextBuilder.class);
    private final AnalysisStatusTracker tracker = new AnalysisStatusTracker();
    private final Clock clock = mock(Clock.class);
    private Instant now = Instant.parse("2026-11-25T12:00:00Z");
    private FailureAnalysisService service;

    @BeforeEach
    void setUp() {
        when(clock.instant()).thenAnswer(call -> now);
        when(quota.status(any())).thenReturn(new QuotaStatus(true, 0, 100, null));
        when(quota.tryReserve(any(), any())).thenReturn(true);
        when(persistence.claim(anyLong())).thenAnswer(call ->
                Optional.of(new AnalysisClaim(call.getArgument(0), 1L, "token", 1)));
        when(persistence.complete(any(), any(), any())).thenReturn(true);
        when(persistence.fail(any(), any(), any(), any(), any(), anyBoolean(), any())).thenAnswer(call ->
                Optional.of((boolean) call.getArgument(5) ? AnalysisStatus.RETRY_PENDING : AnalysisStatus.FAILED));
        when(contextBuilder.build(any())).thenReturn(AnalysisFixtures.context("log"));
    }

    @AfterEach
    void stop() {
        if (service != null) {
            service.stop();
        }
    }

    @Test
    void storesValidAnalyses() {
        claimable(1L, 2L);
        AnalysisCycleReport report = run(new MockLlmProvider(r -> AnalysisFixtures.VALID_ANSWER));

        assertThat(report.completed()).isEqualTo(2);
        verify(persistence, times(2)).complete(any(), any(), any());
    }

    @Test
    void neverRunsMoreThanTwoRequestsAtOnce() {
        claimable(1L, 2L, 3L, 4L, 5L);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger max = new AtomicInteger();
        run(new MockLlmProvider(request -> {
            max.accumulateAndGet(active.incrementAndGet(), Math::max);
            sleep(50);
            active.decrementAndGet();
            return AnalysisFixtures.VALID_ANSWER;
        }));

        assertThat(max.get()).isEqualTo(2);
        verify(persistence, times(5)).complete(any(), any(), any());
    }

    @Test
    void analysisClaimedByAnotherWorkerIsNotSent() {
        claimable(1L);
        when(persistence.claim(1L)).thenReturn(Optional.empty());
        LlmProvider provider = mock(LlmProvider.class);

        assertThat(run(provider).processed()).isZero();
        verify(provider, never()).generate(any());
        verify(quota, never()).tryReserve(any(), any());
    }

    @Test
    void invalidAnswerIsRetriedAndItsTokensAreRecorded() {
        claimable(1L);
        AnalysisCycleReport report = run(new MockLlmProvider(r -> "not json"));

        assertThat(report.retried()).isEqualTo(1);
        verify(persistence).fail(any(), eq("mock"), eq("mock-model"), any(LlmUsage.class),
                startsWith("The model returned an invalid analysis"), eq(true), eq(null));
        verify(persistence, never()).complete(any(), any(), any());
    }

    @Test
    void permanentErrorIsNotRetried() {
        claimable(1L);
        AnalysisCycleReport report = run(failing(Category.INVALID_REQUEST, null));

        assertThat(report.failed()).isEqualTo(1);
        assertThat(report.paused()).isFalse();
    }

    @Test
    void rateLimitPausesAllRequestsForTheRequestedTime() {
        claimable(1L);
        LlmProvider provider = failing(Category.RATE_LIMITED, Duration.ofMinutes(5));
        assertThat(run(provider).paused()).isTrue();
        verify(persistence).fail(any(), any(), any(), any(), any(), eq(true), eq(Duration.ofMinutes(5)));

        now = now.plus(Duration.ofMinutes(4));
        assertThat(service.runCycle().orElseThrow().processed()).isZero();
        now = now.plus(Duration.ofMinutes(2));
        assertThat(service.runCycle().orElseThrow().processed()).isEqualTo(1);
    }

    @Test
    void authenticationErrorStopsTheBatch() {
        claimable(1L, 2L, 3L, 4L, 5L);
        AnalysisCycleReport report = run(failing(Category.AUTHENTICATION, null));

        // At most the two concurrent requests were sent.
        assertThat(report.processed()).isBetween(1, 2);
        assertThat(report.paused()).isTrue();
    }

    @Test
    void resultIsStoredAgainAfterADatabaseError() {
        claimable(1L);
        when(persistence.complete(any(), any(), any()))
                .thenThrow(new DataAccessResourceFailureException("connection lost")).thenReturn(true);
        AtomicInteger calls = new AtomicInteger();
        AnalysisCycleReport report = run(new MockLlmProvider(r -> {
            calls.incrementAndGet();
            return AnalysisFixtures.VALID_ANSWER;
        }));

        assertThat(report.completed()).isEqualTo(1);
        assertThat(calls).hasValue(1);
    }

    @Test
    void lostClaimIsNotCountedAsCompleted() {
        claimable(1L);
        when(persistence.complete(any(), any(), any())).thenReturn(false);

        assertThat(run(new MockLlmProvider(r -> AnalysisFixtures.VALID_ANSWER)).completed()).isZero();
    }

    @Test
    void oneFailureDoesNotAffectOthers() {
        claimable(1L, 2L);
        AtomicInteger calls = new AtomicInteger();
        AnalysisCycleReport report = run(new MockLlmProvider(r -> {
            if (calls.incrementAndGet() == 1) {
                throw new IllegalStateException("unexpected");
            }
            return AnalysisFixtures.VALID_ANSWER;
        }));

        assertThat(report.completed()).isEqualTo(1);
        assertThat(report.retried()).isEqualTo(1);
    }

    @Test
    void dailyLimitStopsNewAnalyses() {
        claimable(1L);
        when(quota.status(any())).thenReturn(new QuotaStatus(true, 100, 100, now));
        LlmProvider provider = mock(LlmProvider.class);

        AnalysisCycleReport report = run(provider);
        assertThat(report.processed()).isZero();
        assertThat(report.quotaExhausted()).isTrue();
        verify(persistence, never()).findEvidenceToAnalyse(anyInt());
        verify(persistence, never()).claim(anyLong());
        verify(provider, never()).generate(any());
    }

    @Test
    void quotaIsReservedBeforeEveryInvocation() {
        claimable(1L, 2L);
        AtomicInteger reserved = new AtomicInteger();
        AtomicInteger invoked = new AtomicInteger();
        List<Boolean> reservedFirst = Collections.synchronizedList(new ArrayList<>());
        when(quota.tryReserve(any(), any())).thenAnswer(call -> {
            reserved.incrementAndGet();
            return true;
        });
        run(new MockLlmProvider(r -> {
            // Counted before the reservations are read, so that the other worker cannot make this fail by chance.
            int invocation = invoked.incrementAndGet();
            reservedFirst.add(reserved.get() >= invocation);
            return AnalysisFixtures.VALID_ANSWER;
        }));

        assertThat(reservedFirst).containsExactly(true, true);
        verify(quota).tryReserve(any(), eq(1L));
        verify(quota).tryReserve(any(), eq(2L));
    }

    @Test
    void withoutReservationTheProviderIsNotInvokedAndNoAttemptIsLost() {
        claimable(1L);
        when(quota.tryReserve(any(), any())).thenReturn(false);
        AtomicInteger calls = new AtomicInteger();

        AnalysisCycleReport report = run(new MockLlmProvider(r -> {
            calls.incrementAndGet();
            return "";
        }));

        assertThat(calls).hasValue(0);
        assertThat(report.processed()).isZero();
        verify(persistence).release(any(), eq("Waiting: the daily AI quota is exhausted"));
        verify(persistence, never()).fail(any(), any(), any(), any(), any(), anyBoolean(), any());
    }

    @Test
    void contextErrorBeforeTheInvocationReservesNothing() {
        claimable(1L);
        when(contextBuilder.build(any())).thenThrow(new IllegalStateException("no context"));

        assertThat(run(new MockLlmProvider()).retried()).isEqualTo(1);
        verify(quota, never()).tryReserve(any(), any());
    }

    @Test
    void enqueueingRaceWithAnotherInstanceIsHarmless() {
        when(persistence.findClaimable(anyInt())).thenReturn(List.of());
        when(persistence.findEvidenceToAnalyse(anyInt())).thenReturn(List.of(10L, 11L));
        when(persistence.enqueue(10L)).thenThrow(new DataIntegrityViolationException("duplicate"));

        assertThat(run(new MockLlmProvider()).enqueued()).isEqualTo(1);
    }

    @Test
    void cyclesDoNotOverlap() throws InterruptedException {
        claimable(1L);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        service = service(new MockLlmProvider(r -> {
            started.countDown();
            await(release);
            return AnalysisFixtures.VALID_ANSWER;
        }));
        Thread first = Thread.ofVirtual().start(() -> service.runCycle());
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

        assertThat(service.runCycle()).isEmpty();
        release.countDown();
        first.join();
    }

    @Test
    void shutdownGivesUnfinishedAnalysesBack() throws InterruptedException {
        claimable(1L);
        CountDownLatch started = new CountDownLatch(1);
        service = service(new MockLlmProvider(r -> {
            started.countDown();
            if (!await(new CountDownLatch(1))) {
                throw new LlmProviderException(Category.TIMEOUT, "interrupted", null);
            }
            return "";
        }));
        Thread cycle = Thread.ofVirtual().start(() -> service.runCycle());
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

        service.stop();

        verify(persistence, timeout(5000)).release(any(), eq(null));
        verify(persistence, never()).fail(any(), any(), any(), any(), any(), anyBoolean(), any());
        cycle.join(5000);
    }

    private void claimable(Long... ids) {
        when(persistence.findClaimable(anyInt())).thenReturn(List.of(ids));
    }

    private AnalysisCycleReport run(LlmProvider provider) {
        service = service(provider);
        return service.runCycle().orElseThrow();
    }

    private FailureAnalysisService service(LlmProvider provider) {
        ProviderPause pause = new ProviderPause(clock);
        FailureAnalysisProcessor processor = new FailureAnalysisProcessor(persistence, contextBuilder,
                new FailureAnalysisPromptBuilder(), quota, provider, new FailureAnalysisParser(new LogSanitizer()),
                new AnalysisEvidenceValidator(), pause);
        return new FailureAnalysisService(persistence, processor, quota, provider, pause, tracker,
                AnalysisFixtures.properties(), clock);
    }

    private static LlmProvider failing(Category category, Duration retryAfter) {
        return new MockLlmProvider(r -> {
            sleep(20);
            throw new LlmProviderException(category, "failed", null, LlmUsage.NONE, retryAfter);
        });
    }

    private static boolean await(CountDownLatch latch) {
        try {
            return latch.await(20, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
