package com.hackathon.agent.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hackathon.agent.ai.context.FailureContextBuilder;
import com.hackathon.agent.ai.entity.FailureAnalysisEntity;
import com.hackathon.agent.ai.llm.LlmProvider;
import com.hackathon.agent.ai.llm.LlmProviderException.Category;
import com.hackathon.agent.ai.llm.LlmProviderException;
import com.hackathon.agent.ai.llm.LlmRequest;
import com.hackathon.agent.ai.llm.LlmResponse;
import com.hackathon.agent.ai.llm.LlmUsage;
import com.hackathon.agent.ai.llm.mock.MockLlmProvider;
import com.hackathon.agent.ai.model.AnalysisClaim;
import com.hackathon.agent.ai.model.AnalysisCycleReport;
import com.hackathon.agent.ai.model.AnalysisStatus;
import com.hackathon.agent.ai.model.FailureAnalysisResult;
import com.hackathon.agent.ai.model.FailureCategory;
import com.hackathon.agent.ai.model.FailureContext;
import com.hackathon.agent.ai.model.RecommendedAction;
import com.hackathon.agent.ai.model.Severity;
import com.hackathon.agent.ai.parser.FailureAnalysisParser;
import com.hackathon.agent.ai.prompt.FailureAnalysisPromptBuilder;
import com.hackathon.agent.ai.repository.FailureAnalysisRepository;
import com.hackathon.agent.ai.service.AiQuotaService;
import com.hackathon.agent.ai.service.AnalysisPersistenceService;
import com.hackathon.agent.ai.service.FailureAnalysisService;
import com.hackathon.agent.entity.PipelineRunEntity;
import com.hackathon.agent.entity.TaskRunEntity;
import com.hackathon.agent.enums.ExecutionStatus;
import com.hackathon.agent.evidence.entity.ContainerEvidenceEntity;
import com.hackathon.agent.evidence.entity.ContainerLogEntity;
import com.hackathon.agent.evidence.entity.FailureEvidenceEntity;
import com.hackathon.agent.evidence.model.ContainerRunState;
import com.hackathon.agent.evidence.model.ContainerType;
import com.hackathon.agent.evidence.model.EvidenceStatus;
import com.hackathon.agent.evidence.model.ExitCodeSource;
import com.hackathon.agent.evidence.model.LogStatus;
import com.hackathon.agent.evidence.repository.ContainerEvidenceRepository;
import com.hackathon.agent.evidence.repository.ContainerLogRepository;
import com.hackathon.agent.evidence.repository.FailureEvidenceRepository;
import com.hackathon.agent.kubernetes.TektonResourceClient;
import com.hackathon.agent.repository.PipelineRunRepository;
import com.hackathon.agent.repository.TaskRunRepository;
import com.hackathon.agent.support.AnalysisFixtures;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Limit;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Analysis cycles through the full application context and the V3 schema; only the provider is mocked.
 */
abstract class AbstractFailureAnalysisIntegrationTest {

    static final Instant START = Instant.parse("2026-11-25T12:00:00Z");

    @MockitoBean
    LlmProvider provider;

    @MockitoBean
    TektonResourceClient phase1Client;

    @MockitoBean
    Clock clock;

    @Autowired
    FailureAnalysisService service;
    @Autowired
    AnalysisPersistenceService persistence;
    @Autowired
    AiQuotaService quota;
    @Autowired
    FailureContextBuilder contextBuilder;
    @Autowired
    FailureAnalysisParser parser;
    @Autowired
    FailureAnalysisRepository analyses;
    @Autowired
    PipelineRunRepository pipelineRuns;
    @Autowired
    TaskRunRepository taskRuns;
    @Autowired
    FailureEvidenceRepository evidence;
    @Autowired
    ContainerEvidenceRepository containers;
    @Autowired
    ContainerLogRepository logs;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    MockMvc mvc;

    Instant now = START;
    private int sequence;

    @BeforeEach
    void setUp() {
        when(clock.instant()).thenAnswer(call -> now);
        when(provider.name()).thenReturn("test-provider");
        when(provider.model()).thenReturn("configured-model");
        when(provider.consumesQuota()).thenReturn(true);
        answer(AnalysisFixtures.VALID_ANSWER);
        cleanUp();
    }

    @AfterEach
    void cleanUp() {
        taskRuns.deleteAll();
        pipelineRuns.deleteAll();
        jdbc.update("delete from ai_invocation");
        jdbc.update("delete from ai_quota");
    }

    // ---------------------------------------------------------------- processing

    @Test
    void analysesEvidenceOnce() {
        FailureEvidenceEntity e = failure("BUILD FAILED");

        AnalysisCycleReport report = cycle();

        assertThat(report.enqueued()).isEqualTo(1);
        assertThat(report.completed()).isEqualTo(1);
        FailureAnalysisEntity a = onlyAnalysis(e);
        assertThat(a.getAnalysisStatus()).isEqualTo(AnalysisStatus.COMPLETED);
        assertThat(a.getFailureCategory()).isEqualTo(FailureCategory.REGISTRY_AUTHENTICATION);
        assertThat(a.getSeverity()).isEqualTo(Severity.HIGH);
        assertThat(a.getConfidence()).isEqualByComparingTo("0.92");
        assertThat(a.getObservedFacts()).containsExactly("step-test exited with code 2");
        assertThat(a.getHypotheses()).containsExactly("The credential may have expired");
        assertThat(a.getSupportingEvidence()).hasSize(2);
        assertThat(a.getRecommendedActions().getFirst().action()).isEqualTo("Renew the credential");
        assertThat(a.getPreventiveRecommendations()).containsExactly("Check credentials before the build");
        assertThat(a.getProvider()).isEqualTo("test-provider");
        assertThat(a.getModel()).isEqualTo("model-that-answered");
        assertThat(a.getPromptVersion()).isEqualTo(FailureAnalysisPromptBuilder.VERSION);
        assertThat(a.getInputTokens()).isEqualTo(100);
        assertThat(a.getOutputTokens()).isEqualTo(20);
        assertThat(a.getProcessingAttempts()).isEqualTo(1);
        assertThat(a.getAnalysedAt()).isEqualTo(START);
        assertThat(a.getClaimToken()).isNull();
        assertThat(a.getLockedUntil()).isNull();

        now = START.plus(Duration.ofHours(2));
        assertThat(cycle().processed()).isZero();
        verify(provider, times(1)).generate(any());
    }

    @Test
    void listsAreStoredAsJsonb() {
        FailureEvidenceEntity e = failure("x");
        cycle();

        if (isPostgres()) {
            assertThat(jdbc.queryForObject("select jsonb_typeof(supporting_evidence) from failure_analysis where failure_evidence_id = ?",
                    String.class, e.getId())).isEqualTo("array");
            assertThat(jdbc.queryForObject("select recommended_actions -> 0 ->> 'action' from failure_analysis where failure_evidence_id = ?",
                    String.class, e.getId())).isEqualTo("Renew the credential");
        }
        assertThat(onlyAnalysis(e).getSupportingEvidence().get(1).containerName()).isNull();
    }

    @Test
    void noTransactionIsOpenWhileTheProviderIsCalled() {
        FailureEvidenceEntity e = failure("x");
        when(provider.generate(any())).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            // Visible to other transactions, so the claim is already committed.
            assertThat(onlyAnalysis(e).getAnalysisStatus()).isEqualTo(AnalysisStatus.PROCESSING);
            return response(AnalysisFixtures.VALID_ANSWER);
        });

        assertThat(cycle().completed()).isEqualTo(1);
    }

    @Test
    void transientErrorsAreRetriedWithBackoffAndThenGivenUp() {
        FailureEvidenceEntity e = failure("x");
        when(provider.generate(any())).thenThrow(new LlmProviderException(Category.UNAVAILABLE, "HTTP 503", null));

        assertThat(cycle().retried()).isEqualTo(1);
        FailureAnalysisEntity a = onlyAnalysis(e);
        assertThat(a.getAnalysisStatus()).isEqualTo(AnalysisStatus.RETRY_PENDING);
        assertThat(a.getNextAttemptAt()).isEqualTo(START.plusSeconds(60));
        assertThat(a.getLastError()).isEqualTo("UNAVAILABLE: HTTP 503");
        assertThat(a.getSummary()).isNull();

        now = START.plusSeconds(59);
        assertThat(cycle().processed()).isZero();
        for (int i = 0; i < 5; i++) {
            now = now.plus(Duration.ofHours(1));
            cycle();
        }
        a = onlyAnalysis(e);
        assertThat(a.getAnalysisStatus()).isEqualTo(AnalysisStatus.FAILED);
        assertThat(a.getProcessingAttempts()).isEqualTo(4);
        verify(provider, times(4)).generate(any());
    }

    @Test
    void retryCanSucceedAndTokensOfAllAttemptsAreSummed() {
        FailureEvidenceEntity e = failure("x");
        answer("not json");
        cycle();
        answer(AnalysisFixtures.VALID_ANSWER);
        now = START.plusSeconds(61);
        cycle();

        FailureAnalysisEntity a = onlyAnalysis(e);
        assertThat(a.getAnalysisStatus()).isEqualTo(AnalysisStatus.COMPLETED);
        assertThat(a.getLastError()).isNull();
        assertThat(a.getInputTokens()).isEqualTo(200);
        assertThat(a.getOutputTokens()).isEqualTo(40);
    }

    @Test
    void permanentErrorIsFinal() {
        FailureEvidenceEntity e = failure("x");
        when(provider.generate(any())).thenThrow(new LlmProviderException(Category.INVALID_REQUEST, "HTTP 400", null));
        cycle();
        now = START.plus(Duration.ofHours(1));
        cycle();

        assertThat(onlyAnalysis(e).getAnalysisStatus()).isEqualTo(AnalysisStatus.FAILED);
        verify(provider, times(1)).generate(any());
    }

    @Test
    void onlyFinishedRecentEvidenceIsAnalysed() {
        withStatus(failure("x"), EvidenceStatus.PENDING, null);
        withStatus(failure("x"), EvidenceStatus.FORBIDDEN, START);
        withStatus(failure("x"), EvidenceStatus.RESOURCE_DELETED, START);
        withStatus(failure("x"), EvidenceStatus.COLLECTED, START.minus(Duration.ofHours(25)));
        assertThat(cycle().enqueued()).isZero();

        withStatus(failure("x"), EvidenceStatus.PARTIAL, START);
        withStatus(failure("x"), EvidenceStatus.POD_DELETED, START);
        withStatus(failure("x"), EvidenceStatus.NO_POD, START);
        assertThat(cycle().completed()).isEqualTo(3);
    }

    @Test
    void batchIsBounded() {
        for (int i = 0; i < 7; i++) {
            failure("x");
        }

        assertThat(cycle().completed()).isEqualTo(5);
        assertThat(cycle().completed()).isEqualTo(2);
    }

    @Test
    void newVersionDoesNotReanalyseAutomatically() {
        FailureEvidenceEntity e = failure("x");
        cycle();
        jdbc.update("update failure_analysis set prompt_version = 'v0'");

        assertThat(cycle().enqueued()).isZero();
        assertThat(analyses.findByFailureEvidenceIdOrderByIdDesc(e.getId())).hasSize(1);
        // It is found when re-analysis is switched on.
        assertThat(analyses.findEvidenceToAnalyse(START.minusSeconds(1), false, 1, FailureAnalysisPromptBuilder.VERSION,
                Limit.of(5))).containsExactly(e.getId());
    }

    // ---------------------------------------------------------------- claiming

    @Test
    void onlyOneOfManyConcurrentWorkersGetsAnAnalysis() throws Exception {
        for (int round = 0; round < 5; round++) {
            Long id = persistence.enqueue(failure("x").getId());

            List<AnalysisClaim> claims = concurrently(8, () -> persistence.claim(id).orElse(null));

            assertThat(claims.stream().filter(Objects::nonNull)).hasSize(1);
            assertThat(analyses.findById(id).orElseThrow().getProcessingAttempts()).isEqualTo(1);
        }
    }

    @Test
    void concurrentInstancesCreateOnlyOneAnalysis() throws Exception {
        FailureEvidenceEntity e = failure("x");

        List<Boolean> created = concurrently(8, () -> {
            try {
                persistence.enqueue(e.getId());
                return true;
            } catch (DataIntegrityViolationException duplicate) {
                return false;
            }
        });

        assertThat(created.stream().filter(c -> c)).hasSize(1);
        assertThat(analyses.findByFailureEvidenceIdOrderByIdDesc(e.getId())).hasSize(1);
    }

    @Test
    void analysisOfADisappearedWorkerIsProcessedAgainAfterItsLease() {
        FailureEvidenceEntity e = failure("x");
        AnalysisClaim lost = persistence.claim(persistence.enqueue(e.getId())).orElseThrow();

        now = START.plusSeconds(599);
        assertThat(cycle().processed()).isZero();
        now = START.plusSeconds(601);
        assertThat(cycle().completed()).isEqualTo(1);
        assertThat(onlyAnalysis(e).getProcessingAttempts()).isEqualTo(2);

        // The old worker wakes up: its result must not replace the new one.
        FailureAnalysisResult late = parser.parse(AnalysisFixtures.VALID_ANSWER.replace("HIGH\",", "LOW\","));
        assertThat(persistence.complete(lost, late, response("x"))).isFalse();
        assertThat(persistence.fail(lost, "p", "m", LlmUsage.NONE, "late", true, null)).isEmpty();
        assertThat(onlyAnalysis(e).getSeverity()).isEqualTo(Severity.HIGH);
        assertThat(onlyAnalysis(e).getAnalysisStatus()).isEqualTo(AnalysisStatus.COMPLETED);
    }

    @Test
    void analysisThatAlwaysLosesItsWorkerIsGivenUp() {
        FailureEvidenceEntity e = failure("x");
        Long id = persistence.enqueue(e.getId());
        for (int i = 0; i < 4; i++) {
            assertThat(persistence.claim(id)).isPresent();
            now = now.plusSeconds(601);
        }

        cycle();

        assertThat(onlyAnalysis(e).getAnalysisStatus()).isEqualTo(AnalysisStatus.FAILED);
        assertThat(onlyAnalysis(e).getLastError()).contains("interrupted");
        verify(provider, never()).generate(any());
    }

    @Test
    void releasedAnalysisDoesNotLoseAnAttempt() {
        FailureEvidenceEntity e = failure("x");
        persistence.release(persistence.claim(persistence.enqueue(e.getId())).orElseThrow(), null);

        assertThat(onlyAnalysis(e).getAnalysisStatus()).isEqualTo(AnalysisStatus.PENDING);
        assertThat(onlyAnalysis(e).getProcessingAttempts()).isZero();
        assertThat(cycle().completed()).isEqualTo(1);
    }

    // ---------------------------------------------------------------- quota (limit: 8, see the subclasses)

    @Test
    void everyInvocationCountsIncludingRetriesAndFailures() {
        failure("x");
        when(provider.generate(any())).thenThrow(new LlmProviderException(Category.TIMEOUT, "timed out", null));
        for (int i = 0; i < 6; i++) {
            cycle();
            now = now.plus(Duration.ofHours(1));
        }

        // One analysis, but four invocations.
        verify(provider, times(4)).generate(any());
        assertThat(quota.status(provider).used()).isEqualTo(4);
        assertThat(jdbc.queryForObject("select count(distinct failure_analysis_id) from ai_invocation", Integer.class)).isEqualTo(1);
    }

    @Test
    void invalidAnswersCount() {
        failure("x");
        answer("not json");
        cycle();
        assertThat(quota.status(provider).used()).isEqualTo(1);
    }

    @Test
    void reservationIsCommittedBeforeTheCall() {
        failure("x");
        when(provider.generate(any())).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(jdbc.queryForObject("select count(*) from ai_invocation", Integer.class)).isEqualTo(1);
            return response(AnalysisFixtures.VALID_ANSWER);
        });

        assertThat(cycle().completed()).isEqualTo(1);
    }

    @Test
    void scheduledAnalysesStopAtTheLimitAndContinueWhenQuotaBecomesAvailable() {
        for (int i = 0; i < 10; i++) {
            failure("x");
        }
        cycle();
        AnalysisCycleReport atLimit = cycle();
        assertThat(atLimit.quotaExhausted()).isTrue();
        assertThat(cycle().processed()).isZero();
        verify(provider, times(8)).generate(any());
        assertThat(quota.status(provider).availableAt()).isEqualTo(START.plus(Duration.ofHours(24)));

        now = START.plus(Duration.ofHours(24));
        assertThat(cycle().processed()).isEqualTo(2);
        verify(provider, times(10)).generate(any());
    }

    @Test
    void retriesStopAtTheLimitWithoutLosingAttempts() {
        FailureEvidenceEntity e = failure("x");
        when(provider.generate(any())).thenThrow(new LlmProviderException(Category.UNAVAILABLE, "HTTP 503", null));
        cycle();
        useQuota(7);

        now = START.plus(Duration.ofHours(1));
        cycle();

        assertThat(onlyAnalysis(e).getAnalysisStatus()).isEqualTo(AnalysisStatus.RETRY_PENDING);
        assertThat(onlyAnalysis(e).getProcessingAttempts()).isEqualTo(1);
        verify(provider, times(1)).generate(any());
    }

    @Test
    void manualRequestIsRejectedWhenTheQuotaIsExhausted() throws Exception {
        FailureEvidenceEntity e = failure("x");
        useQuota(8);
        now = START.plus(Duration.ofHours(1));

        mvc.perform(post("/api/v1/failure-evidence/{id}/analyse", e.getId())).andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", String.valueOf(23 * 3600)))
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(jsonPath("$.message").value(containsString("daily AI quota of 8 invocations is exhausted")));
        assertThat(analyses.count()).isZero();
        mvc.perform(get("/actuator/health")).andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void forceDoesNotBypassTheQuota() throws Exception {
        FailureEvidenceEntity e = failure("x");
        cycle();
        useQuota(7);
        clearInvocations(provider);

        mvc.perform(post("/api/v1/failure-evidence/{id}/analyse", e.getId()).param("force", "true"))
                .andExpect(status().isTooManyRequests());
        cycle();
        assertThat(analyses.findByFailureEvidenceIdOrderByIdDesc(e.getId())).hasSize(1);
        verify(provider, never()).generate(any());
    }

    @Test
    void acceptedRequestWaitsWhenTheQuotaIsUsedUpBeforeItIsProcessed() throws Exception {
        FailureEvidenceEntity e = failure("x");
        cycle();
        mvc.perform(post("/api/v1/failure-evidence/{id}/analyse", e.getId()).param("force", "true"))
                .andExpect(status().isAccepted());
        useQuota(7);
        clearInvocations(provider);

        cycle();

        verify(provider, never()).generate(any());
        assertThat(analyses.findByFailureEvidenceIdOrderByIdDesc(e.getId()).getFirst().getAnalysisStatus())
                .isEqualTo(AnalysisStatus.PENDING);
        // A request already in progress is returned, not rejected.
        mvc.perform(post("/api/v1/failure-evidence/{id}/analyse", e.getId())).andExpect(status().isAccepted());
    }

    @Test
    void forcedReanalysisConsumesQuota() throws Exception {
        FailureEvidenceEntity e = failure("x");
        cycle();
        mvc.perform(post("/api/v1/failure-evidence/{id}/analyse", e.getId()).param("force", "true"));
        cycle();

        assertThat(quota.status(provider).used()).isEqualTo(2);
    }

    @Test
    void concurrentWorkersNeverExceedTheLimit() throws Exception {
        // Includes the race to create the lock row of a provider that was never used before.
        List<Boolean> reservations = concurrently(24, () -> quota.tryReserve(provider, null));

        assertThat(reservations.stream().filter(r -> r)).hasSize(8);
        assertThat(quota.status(provider).used()).isEqualTo(8);
        assertThat(quota.status(provider).isExhausted()).isTrue();
    }

    @Test
    void concurrentCyclesOfSeveralInstancesNeverExceedTheLimit() throws Exception {
        for (int i = 0; i < 20; i++) {
            failure("x");
        }
        // Every worker believes there is quota left, as workers of other instances do.
        List<Long> ids = new ArrayList<>();
        evidence.findAll().forEach(e -> ids.add(persistence.enqueue(e.getId())));
        AtomicInteger calls = new AtomicInteger();
        CyclicBarrier barrier = new CyclicBarrier(ids.size());
        try (ExecutorService executor = Executors.newFixedThreadPool(ids.size())) {
            List<Future<?>> futures = new ArrayList<>();
            for (Long id : ids) {
                futures.add(executor.submit(() -> {
                    persistence.claim(id).orElseThrow();
                    barrier.await();
                    if (quota.tryReserve(provider, id)) {
                        calls.incrementAndGet();
                    }
                    return null;
                }));
            }
            for (Future<?> future : futures) {
                future.get();
            }
        }

        assertThat(calls).hasValue(8);
    }

    @Test
    void quotaIsSeparatePerProviderAndFreeProvidersAreExempt() {
        useQuota(8);
        LlmProvider other = mock(LlmProvider.class);
        when(other.name()).thenReturn("other-provider");
        when(other.consumesQuota()).thenReturn(true);

        assertThat(quota.tryReserve(provider, null)).isFalse();
        assertThat(quota.tryReserve(other, null)).isTrue();
        for (int i = 0; i < 20; i++) {
            assertThat(quota.tryReserve(new MockLlmProvider(), null)).isTrue();
        }
        assertThat(jdbc.queryForObject("select count(*) from ai_invocation where provider = 'mock'", Integer.class)).isZero();
        assertThat(quota.status(new MockLlmProvider()).isExhausted()).isFalse();
    }

    @Test
    void quotaWindowIsRolling() {
        useQuota(4);
        now = START.plus(Duration.ofHours(12));
        useQuota(4);
        assertThat(quota.tryReserve(provider, null)).isFalse();

        now = START.plus(Duration.ofHours(24));
        assertThat(quota.status(provider).remaining()).isEqualTo(4);
        assertThat(quota.tryReserve(provider, null)).isTrue();
    }

    @Test
    void deletingAnalysesDoesNotFreeQuota() {
        failure("x");
        cycle();

        taskRuns.deleteAll();

        assertThat(analyses.count()).isZero();
        assertThat(quota.status(provider).used()).isEqualTo(1);
    }

    @Test
    void shutdownDoesNotGiveReservedQuotaBack() {
        FailureEvidenceEntity e = failure("x");
        AnalysisClaim claim = persistence.claim(persistence.enqueue(e.getId())).orElseThrow();
        assertThat(quota.tryReserve(provider, claim.analysisId())).isTrue();

        persistence.release(claim, null);

        assertThat(onlyAnalysis(e).getProcessingAttempts()).isZero();
        assertThat(quota.status(provider).used()).isEqualTo(1);
    }

    @Test
    void healthShowsAnExhaustedQuota() throws Exception {
        useQuota(8);
        cycle();

        mvc.perform(get("/actuator/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components.aiAnalysis.details.state").value("QUOTA_EXHAUSTED"));
    }

    // ---------------------------------------------------------------- evidence validation

    @Test
    void fabricatedAnalysisIsStoredAsInsufficientEvidence() {
        FailureEvidenceEntity e = failure("BUILD FAILED");
        answer(AnalysisFixtures.VALID_ANSWER.replace("exitCode 2", "exitCode 137")
                .replace("denied: authentication required", "no space left on device").replace("\"HIGH\",\n  \"conf", "\"CRITICAL\",\n  \"conf"));
        cycle();

        FailureAnalysisEntity a = onlyAnalysis(e);
        assertThat(a.getAnalysisStatus()).isEqualTo(AnalysisStatus.COMPLETED);
        assertThat(a.getFailureCategory()).isEqualTo(FailureCategory.UNKNOWN);
        assertThat(a.getRootCause()).startsWith("Insufficient evidence");
        assertThat(a.getSupportingEvidence()).isEmpty();
        assertThat(a.getConfidence()).isEqualByComparingTo("0.3");
        assertThat(a.getSeverity()).isEqualTo(Severity.MEDIUM);
        assertThat(a.getHypotheses()).anyMatch(h -> h.startsWith("Unconfirmed root cause"));
        // Reduced, not repeated: no further invocation is spent.
        verify(provider, times(1)).generate(any());
    }

    @Test
    void storedBuildLogReachesTheModelAndItsConclusionIsKept() throws Exception {
        FailureEvidenceEntity e = failure(
                new ClassPathResource("logs/compilation-failure.txt").getContentAsString(StandardCharsets.UTF_8));
        answer("""
                {
                  "summary": "compileJava failed because println(\\"I get fired); in DemoController.java has no closing quote.",
                  "failureCategory": "COMPILATION",
                  "rootCause": "System.out.println(\\"I get fired); at DemoController.java:16 is an unclosed string literal, which also causes \\"';' expected\\" at line 21.",
                  "severity": "HIGH",
                  "confidence": 0.97,
                  "observedFacts": ["step-test exited with code 2"],
                  "hypotheses": [],
                  "supportingEvidence": [{"source": "CONTAINER_LOG", "containerName": "step-test",
                    "observation": "/app/src/main/java/com/hackathon/demo/controller/DemoController.java:16: error: unclosed string literal"}],
                  "recommendedActions": [{"priority": "HIGH", "action": "Close the string literal", "reason": "It does not compile"}],
                  "preventiveRecommendations": []
                }
                """);
        cycle();

        ArgumentCaptor<LlmRequest> request = ArgumentCaptor.forClass(LlmRequest.class);
        verify(provider).generate(request.capture());
        assertThat(request.getValue().userPrompt())
                .contains("DemoController.java:16: error: unclosed string literal")
                .contains("DemoController.java:21: error: ';' expected")
                .contains("BUILD FAILED in 49s");
        FailureAnalysisEntity a = onlyAnalysis(e);
        assertThat(a.getFailureCategory()).isEqualTo(FailureCategory.COMPILATION);
        assertThat(a.getRootCause()).contains("unclosed string literal");
        assertThat(a.getSummary()).startsWith("compileJava failed");
        assertThat(a.getConfidence()).isEqualByComparingTo("0.97");
        assertThat(a.getSeverity()).isEqualTo(Severity.HIGH);
        assertThat(a.getSupportingEvidence()).hasSize(1);
    }

    @Test
    void genericAnswerToACompilerErrorIsStoredConsistently() throws Exception {
        FailureEvidenceEntity e = failure(
                new ClassPathResource("logs/compilation-failure.txt").getContentAsString(StandardCharsets.UTF_8));
        answer("""
                {
                  "summary": "Insufficient evidence to determine why the TaskRun failed.",
                  "failureCategory": "UNKNOWN",
                  "rootCause": "Insufficient evidence: the collected evidence does not confirm a root cause.",
                  "severity": "MEDIUM",
                  "confidence": 0.3,
                  "observedFacts": ["step-test exited with code 2"],
                  "hypotheses": [],
                  "supportingEvidence": [{"source": "CONTAINER_LOG", "containerName": "step-test",
                    "observation": "/app/src/main/java/com/hackathon/demo/controller/DemoController.java:16: error: unclosed string literal"}],
                  "recommendedActions": [{"priority": "HIGH", "action": "Close the string literal in DemoController.java", "reason": "It does not compile"}],
                  "preventiveRecommendations": []
                }
                """);
        cycle();

        FailureAnalysisEntity a = onlyAnalysis(e);
        assertThat(a.getSummary()).isEqualTo(
                "Compilation failed in DemoController.java:16: unclosed string literal (missing closing quote).");
        assertThat(a.getRootCause()).contains("step-test").contains("DemoController.java:16: unclosed string literal");
        assertThat(a.getFailureCategory()).isEqualTo(FailureCategory.COMPILATION);
        assertThat(a.getConfidence()).isEqualByComparingTo("0.8");
        assertThat(a.getSupportingEvidence()).hasSize(1);
        assertThat(a.getRecommendedActions()).extracting(RecommendedAction::action).containsExactly(
                "Fix the unclosed string literal in DemoController.java, line 16",
                "Run ./gradlew clean bootJar locally and confirm that compilation succeeds",
                "Add or retain a compile validation step before the container image build");
        verify(provider, times(1)).generate(any());
    }

    // ---------------------------------------------------------------- schema

    @Test
    void schemaRejectsInconsistentRows() {
        Long id = persistence.enqueue(failure("x").getId());

        assertThatThrownBy(() -> jdbc.update("update failure_analysis set failure_evidence_id = 999999 where id = ?", id))
                .isInstanceOf(DataIntegrityViolationException.class);
        for (String change : List.of("analysis_status = 'OTHER'", "severity = 'EXTREME'", "confidence = 1.5",
                "confidence = -0.1", "revision = -1", "input_tokens = -1", "processing_attempts = -1",
                "analysis_status = 'COMPLETED'", "analysis_status = 'PROCESSING'", "analysis_status = 'RETRY_PENDING'")) {
            assertThatThrownBy(() -> jdbc.update("update failure_analysis set " + change + " where id = ?", id))
                    .as(change).isInstanceOf(DataIntegrityViolationException.class);
        }
    }

    @Test
    void sameEvidenceAndVersionIsUnique() {
        FailureEvidenceEntity e = failure("x");
        persistence.enqueue(e.getId());

        assertThatThrownBy(() -> persistence.enqueue(e.getId())).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void deletingEvidenceDeletesItsAnalysis() {
        FailureEvidenceEntity e = failure("x");
        cycle();

        evidence.deleteById(e.getId());

        assertThat(analyses.count()).isZero();
    }

    // ---------------------------------------------------------------- context

    @Test
    void contextContainsEverythingKnownAboutTheFailure() {
        FailureEvidenceEntity e = failure("compiling\nBUILD FAILED");
        TaskRunEntity other = taskRun("other-" + sequence++, taskRuns.findById(e.getTaskRunId()).orElseThrow().getPipelineRunUid());
        other.setFailureReason("Failed first");
        taskRuns.save(other);

        FailureContext context = contextBuilder.build(e.getId());

        assertThat(context.pipelineRun().status()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(context.pipelineRun().pipelineName()).isEqualTo("build");
        assertThat(context.taskRun().taskName()).isEqualTo("build-and-push");
        assertThat(context.taskRun().attempt()).isZero();
        assertThat(context.taskRun().totalAttempts()).isEqualTo(1);
        assertThat(context.pod().phase()).isEqualTo("Failed");
        assertThat(context.containers()).extracting(FailureContext.Container::name)
                .containsExactly("step-test", "step-publish");
        assertThat(context.containers().getFirst().primaryFailure()).isTrue();
        assertThat(context.containers().getFirst().exitCode()).isEqualTo(2);
        assertThat(context.logs()).containsExactly(
                new FailureContext.ContainerLog("step-test", false, LogStatus.COLLECTED, false, "compiling\nBUILD FAILED"),
                new FailureContext.ContainerLog("step-test", true, LogStatus.NOT_AVAILABLE, false, null));
        assertThat(context.otherTaskRuns()).extracting(FailureContext.OtherTaskRun::failureReason).containsExactly("Failed first");
        assertThat(context.limitations()).isEmpty();
    }

    @Test
    void contextNamesWhatIsMissing() {
        FailureEvidenceEntity e = failure("x");
        containers.deleteAll();
        withStatus(e, EvidenceStatus.POD_DELETED, START);

        assertThat(contextBuilder.build(e.getId()).limitations()).hasSize(3)
                .anyMatch(l -> l.contains("Pod was already deleted")).anyMatch(l -> l.contains("No container logs"));
    }

    @Test
    void nothingSecretReachesTheProvider() {
        FailureEvidenceEntity e = failure("log with password=logsecret1");
        TaskRunEntity taskRun = taskRuns.findById(e.getTaskRunId()).orElseThrow();
        taskRun.setFailureMessage("step failed: token=messagesecret2");
        taskRuns.save(taskRun);
        PipelineRunEntity run = pipelineRuns.findByPipelineRunUid(taskRun.getPipelineRunUid()).orElseThrow();
        run.setFailureMessage("https://user:urlsecret3@host/x failed");
        pipelineRuns.save(run);

        cycle();

        ArgumentCaptor<LlmRequest> request = ArgumentCaptor.forClass(LlmRequest.class);
        verify(provider).generate(request.capture());
        assertThat(request.getValue().userPrompt()).contains("[REDACTED]")
                .doesNotContain("logsecret1").doesNotContain("messagesecret2").doesNotContain("urlsecret3");
    }

    @Test
    void contextIsBoundedAndKeepsTheEndOfLogs() {
        FailureEvidenceEntity e = failure("first line\n" + "some output\n".repeat(5000) + "the final error");
        for (int i = 0; i < 25; i++) {
            ContainerLogEntity log = log(container(e, "step-extra-" + i, i + 5, false), "filler line\n".repeat(3000));
            logs.save(log);
        }

        FailureContext context = contextBuilder.build(e.getId());

        assertThat(context.containers()).hasSize(20);
        int chars = context.logs().stream().filter(l -> l.content() != null).mapToInt(l -> l.content().length()).sum();
        assertThat(chars).isLessThanOrEqualTo(60000).isGreaterThan(50000);
        FailureContext.ContainerLog primary = context.logs().getFirst();
        assertThat(primary.content()).hasSizeLessThanOrEqualTo(30000).startsWith("some output").endsWith("the final error");
        assertThat(primary.truncated()).isTrue();
        assertThat(context.logs()).anyMatch(l -> l.availability() == LogStatus.SKIPPED_LIMIT);
        assertThat(context.limitations()).anyMatch(l -> l.contains("shortened")).anyMatch(l -> l.contains("20 of"));
    }

    @Test
    void expiredLogsAreNotUsed() {
        FailureEvidenceEntity e = failure("old content");
        now = START.plus(Duration.ofDays(15));

        assertThat(contextBuilder.build(e.getId()).logs().getFirst().availability()).isEqualTo(LogStatus.EXPIRED);
        assertThat(contextBuilder.build(e.getId()).logs().getFirst().content()).isNull();
    }

    // ---------------------------------------------------------------- REST

    @Test
    void servesAnalyses() throws Exception {
        FailureEvidenceEntity e = failure("x");
        cycle();
        Long id = onlyAnalysis(e).getId();

        mvc.perform(get("/api/v1/failure-analyses/{id}", id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.failureCategory").value("REGISTRY_AUTHENTICATION"))
                .andExpect(jsonPath("$.confidence").value(0.92))
                .andExpect(jsonPath("$.confidenceLevel").value("HIGH"))
                .andExpect(jsonPath("$.taskName").value("build-and-push"))
                .andExpect(jsonPath("$.supportingEvidence[0].source").value("CONTAINER_STATUS"))
                .andExpect(jsonPath("$.recommendedActions[0].priority").value("HIGH"))
                .andExpect(jsonPath("$.analysedAt").value("2026-11-25T12:00:00Z"))
                .andExpect(jsonPath("$.claimToken").doesNotExist())
                .andExpect(content().string(not(containsString("failure_context"))));
        mvc.perform(get("/api/v1/tasks/{id}/analysis", e.getTaskRunId())).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id));
    }

    @Test
    void listsFiltersSortsAndPaginates() throws Exception {
        failure("x");
        failure("x");
        cycle();
        answer(AnalysisFixtures.VALID_ANSWER.replace("REGISTRY_AUTHENTICATION", "TIMEOUT").replace("\"HIGH\",\n  \"conf", "\"LOW\",\n  \"conf"));
        failure("x");
        cycle();
        when(provider.generate(any())).thenThrow(new LlmProviderException(Category.INVALID_REQUEST, "HTTP 400", null));
        failure("x");
        cycle();

        mvc.perform(get("/api/v1/failure-analyses")).andExpect(jsonPath("$.totalElements").value(4));
        mvc.perform(get("/api/v1/failure-analyses").param("category", "timeout"))
                .andExpect(jsonPath("$.totalElements").value(1)).andExpect(jsonPath("$.content[0].severity").value("LOW"));
        mvc.perform(get("/api/v1/failure-analyses").param("severity", "HIGH").param("status", "completed"))
                .andExpect(jsonPath("$.totalElements").value(2));
        mvc.perform(get("/api/v1/failure-analyses").param("status", "FAILED"))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].failureCategory").doesNotExist());
        mvc.perform(get("/api/v1/failure-analyses").param("size", "3").param("page", "1").param("sort", "confidence,desc"))
                .andExpect(jsonPath("$.content.length()").value(1)).andExpect(jsonPath("$.totalPages").value(2));
    }

    @Test
    void rejectsInvalidRequests() throws Exception {
        mvc.perform(get("/api/v1/failure-analyses").param("severity", "EXTREME")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("severity"));
        mvc.perform(get("/api/v1/failure-analyses").param("sort", "claimToken")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/failure-analyses/999999")).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/tasks/999999/analysis")).andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/failure-evidence/999999/analyse")).andExpect(status().isNotFound());
    }

    @Test
    void manualRequestIsQueuedAndNeverDuplicated() throws Exception {
        FailureEvidenceEntity e = withStatus(failure("x"), EvidenceStatus.COLLECTED, START.minus(Duration.ofDays(3)));

        mvc.perform(post("/api/v1/failure-evidence/{id}/analyse", e.getId())).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("PENDING"));
        mvc.perform(post("/api/v1/failure-evidence/{id}/analyse", e.getId()).param("force", "true"))
                .andExpect(status().isAccepted());
        verify(provider, never()).generate(any());
        assertThat(analyses.findByFailureEvidenceIdOrderByIdDesc(e.getId())).hasSize(1);

        cycle();
        assertThat(onlyAnalysis(e).getAnalysisStatus()).isEqualTo(AnalysisStatus.COMPLETED);
        mvc.perform(post("/api/v1/failure-evidence/{id}/analyse", e.getId())).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(containsString("force=true")));
    }

    @Test
    void forcedReanalysisCreatesANewRevision() throws Exception {
        FailureEvidenceEntity e = failure("x");
        cycle();
        clearInvocations(provider);

        mvc.perform(post("/api/v1/failure-evidence/{id}/analyse", e.getId()).param("force", "true"))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.revision").value(1));
        cycle();

        assertThat(analyses.findByFailureEvidenceIdOrderByIdDesc(e.getId())).hasSize(2)
                .allMatch(a -> a.getAnalysisStatus() == AnalysisStatus.COMPLETED);
        mvc.perform(get("/api/v1/tasks/{id}/analysis", e.getTaskRunId())).andExpect(jsonPath("$.revision").value(1));
        verify(provider, times(1)).generate(any());
    }

    @Test
    void failedAnalysisCanBeRequestedAgain() throws Exception {
        FailureEvidenceEntity e = failure("x");
        when(provider.generate(any())).thenThrow(new LlmProviderException(Category.INVALID_REQUEST, "HTTP 400", null));
        cycle();

        mvc.perform(post("/api/v1/failure-evidence/{id}/analyse", e.getId())).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.revision").value(1));
    }

    @Test
    void evidenceStillBeingCollectedCannotBeAnalysed() throws Exception {
        FailureEvidenceEntity e = withStatus(failure("x"), EvidenceStatus.PENDING, null);

        mvc.perform(post("/api/v1/failure-evidence/{id}/analyse", e.getId())).andExpect(status().isConflict());
    }

    @Test
    void overallHealthDoesNotDependOnTheProvider() throws Exception {
        failure("x");
        when(provider.generate(any())).thenThrow(new LlmProviderException(Category.UNAVAILABLE, "HTTP 503", null));
        cycle();

        mvc.perform(get("/actuator/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components.aiAnalysis.details.state").value("DEGRADED"));
    }

    // ---------------------------------------------------------------- helpers

    private AnalysisCycleReport cycle() {
        return service.runCycle().orElseThrow();
    }

    private void useQuota(int invocations) {
        for (int i = 0; i < invocations; i++) {
            assertThat(quota.tryReserve(provider, null)).isTrue();
        }
    }

    private void answer(String text) {
        doAnswer(call -> response(text)).when(provider).generate(any());
    }

    private static LlmResponse response(String text) {
        return new LlmResponse(text, "test-provider", "model-that-answered", new LlmUsage(100, 20));
    }

    private FailureAnalysisEntity onlyAnalysis(FailureEvidenceEntity e) {
        List<FailureAnalysisEntity> rows = analyses.findByFailureEvidenceIdOrderByIdDesc(e.getId());
        assertThat(rows).hasSize(1);
        return rows.getFirst();
    }

    private boolean isPostgres() {
        return Boolean.TRUE.equals(jdbc.execute((ConnectionCallback<Boolean>) c ->
                c.getMetaData().getDatabaseProductName().equals("PostgreSQL")));
    }

    private static <R> List<R> concurrently(int threads, Callable<R> task) throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(threads);
        try (ExecutorService executor = Executors.newFixedThreadPool(threads)) {
            List<Future<R>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(executor.submit(() -> {
                    barrier.await();
                    return task.call();
                }));
            }
            List<R> results = new ArrayList<>();
            for (Future<R> future : futures) {
                results.add(future.get());
            }
            return results;
        }
    }

    /** A failed TaskRun with collected evidence: two steps, the first one failed and has a log. */
    private FailureEvidenceEntity failure(String logText) {
        String name = "run-" + sequence++;
        PipelineRunEntity run = new PipelineRunEntity();
        run.setPipelineRunUid("pr-" + name);
        run.setPipelineRunName(name);
        run.setPipelineName("build");
        run.setNamespace("ci");
        run.setStatus(ExecutionStatus.FAILED);
        run.setCreatedAt(START);
        run.setUpdatedAt(START);
        pipelineRuns.save(run);
        TaskRunEntity taskRun = taskRun(name, run.getPipelineRunUid());

        FailureEvidenceEntity e = new FailureEvidenceEntity();
        e.setTaskRunId(taskRun.getId());
        e.setStatus(EvidenceStatus.COLLECTED);
        e.setPodName(name + "-pod");
        e.setPodUid("pod-" + name);
        e.setPodPhase("Failed");
        e.setCollectedAt(START);
        e.setCreatedAt(START);
        e.setUpdatedAt(START);
        evidence.save(e);
        container(e, "step-publish", 1, false);
        ContainerEvidenceEntity failed = container(e, "step-test", 0, true);
        logs.save(log(failed, logText));
        ContainerLogEntity previous = log(failed, null);
        previous.setPrevious(true);
        previous.setStatus(LogStatus.NOT_AVAILABLE);
        logs.save(previous);
        return e;
    }

    private TaskRunEntity taskRun(String name, String pipelineRunUid) {
        TaskRunEntity taskRun = new TaskRunEntity();
        taskRun.setTaskRunUid("tr-" + name);
        taskRun.setTaskRunName(name + "-build");
        taskRun.setTaskName("build-and-push");
        taskRun.setPipelineRunUid(pipelineRunUid);
        taskRun.setPipelineRunName(name);
        taskRun.setNamespace("ci");
        taskRun.setStatus(ExecutionStatus.FAILED);
        taskRun.setFailureReason("Failed");
        taskRun.setFailureMessage("denied: authentication required");
        taskRun.setCreatedAt(START);
        taskRun.setUpdatedAt(START);
        return taskRuns.save(taskRun);
    }

    private FailureEvidenceEntity withStatus(FailureEvidenceEntity e, EvidenceStatus status, Instant collectedAt) {
        e.setStatus(status);
        e.setCollectedAt(collectedAt);
        return evidence.save(e);
    }

    private ContainerEvidenceEntity container(FailureEvidenceEntity e, String name, int ordinal, boolean primary) {
        ContainerEvidenceEntity c = new ContainerEvidenceEntity();
        c.setEvidenceId(e.getId());
        c.setContainerName(name);
        c.setContainerType(ContainerType.STEP);
        c.setOrdinal(ordinal);
        c.setState(ContainerRunState.TERMINATED);
        c.setExitCode(primary ? 2 : 1);
        c.setExitCodeSource(ExitCodeSource.POD);
        c.setPrimaryFailure(primary);
        c.setCreatedAt(START);
        c.setUpdatedAt(START);
        return containers.save(c);
    }

    private ContainerLogEntity log(ContainerEvidenceEntity container, String text) {
        ContainerLogEntity log = new ContainerLogEntity();
        log.setContainerEvidenceId(container.getId());
        log.setStatus(LogStatus.COLLECTED);
        log.setContent(text);
        log.setExpiresAt(START.plus(Duration.ofDays(14)));
        log.setCreatedAt(START);
        log.setUpdatedAt(START);
        return log;
    }
}
