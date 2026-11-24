package com.hackathon.agent.evidence.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hackathon.agent.collector.TektonCollectionService;
import com.hackathon.agent.config.SchedulingConfig;
import com.hackathon.agent.evidence.service.FailureEvidenceService;
import com.hackathon.agent.scheduler.TektonCollectionScheduler;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.task.TaskSchedulingAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.TaskScheduler;

/**
 * The Phase 1 collector must keep its schedule whatever the evidence worker does.
 */
class SchedulingIsolationTest {

    private final TektonCollectionService phase1 = mock(TektonCollectionService.class);
    private final FailureEvidenceService evidence = mock(FailureEvidenceService.class);
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(TaskSchedulingAutoConfiguration.class))
            .withUserConfiguration(SchedulingConfig.class, TektonCollectionScheduler.class,
                    FailureEvidenceScheduler.class)
            .withBean(TektonCollectionService.class, () -> phase1)
            .withBean(FailureEvidenceService.class, () -> evidence)
            .withPropertyValues("monitoring.enabled=true", "monitoring.polling-interval-ms=20",
                    "monitoring.initial-delay-ms=0", "evidence.polling-interval-ms=20", "evidence.initial-delay-ms=0");

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void blockedEvidenceCycleDoesNotDelayPhase1OrOverlap(boolean virtualThreads) {
        CountDownLatch release = new CountDownLatch(1);
        when(evidence.runCycle()).thenAnswer(call -> {
            release.await();
            return Optional.empty();
        });

        runner.withPropertyValues("evidence.enabled=true", "spring.threads.virtual.enabled=" + virtualThreads)
                .run(context -> {
                    verify(evidence, timeout(5000)).runCycle();
                    verify(phase1, timeout(5000).atLeast(10)).runCycle();
                    verify(evidence, times(1)).runCycle();
                    assertThat(context).hasSingleBean(TaskScheduler.class);
                    release.countDown();
                    verify(evidence, timeout(5000).atLeast(2)).runCycle();
                });
    }

    @Test
    void failingEvidenceCycleIsTriggeredAgain() {
        when(evidence.runCycle()).thenThrow(new IllegalStateException("boom"));

        runner.withPropertyValues("evidence.enabled=true")
                .run(context -> verify(evidence, timeout(5000).atLeast(3)).runCycle());
    }

    @Test
    void nothingIsScheduledWhenDisabled() {
        runner.run(context -> {
            assertThat(context).doesNotHaveBean(FailureEvidenceScheduler.class);
            verify(phase1, timeout(5000).atLeast(3)).runCycle();
            verify(evidence, times(0)).runCycle();
        });
    }

    @Test
    void shutdownInterruptsARunningCycle() {
        CountDownLatch interrupted = new CountDownLatch(1);
        when(evidence.runCycle()).thenAnswer(call -> {
            try {
                new CountDownLatch(1).await();
            } catch (InterruptedException e) {
                interrupted.countDown();
            }
            return Optional.empty();
        });

        runner.withPropertyValues("evidence.enabled=true")
                .run(context -> verify(evidence, timeout(5000).atLeast(1)).runCycle());

        await().until(() -> interrupted.getCount() == 0);
    }
}
