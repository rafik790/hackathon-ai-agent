package com.hackathon.agent.ai.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hackathon.agent.ai.service.FailureAnalysisService;
import com.hackathon.agent.collector.TektonCollectionService;
import com.hackathon.agent.config.AiProperties;
import com.hackathon.agent.config.SchedulingConfig;
import com.hackathon.agent.evidence.scheduler.FailureEvidenceScheduler;
import com.hackathon.agent.evidence.service.FailureEvidenceService;
import com.hackathon.agent.scheduler.TektonCollectionScheduler;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.task.TaskSchedulingAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.TaskScheduler;

class FailureAnalysisSchedulerTest {

    private final FailureAnalysisService analysis = mock(FailureAnalysisService.class);
    private final FailureEvidenceService evidence = mock(FailureEvidenceService.class);
    private final TektonCollectionService phase1 = mock(TektonCollectionService.class);
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(TaskSchedulingAutoConfiguration.class))
            .withUserConfiguration(SchedulingConfig.class, TektonCollectionScheduler.class,
                    FailureEvidenceScheduler.class, FailureAnalysisScheduler.class, Properties.class)
            .withBean(FailureAnalysisService.class, () -> analysis)
            .withBean(FailureEvidenceService.class, () -> evidence)
            .withBean(TektonCollectionService.class, () -> phase1)
            .withPropertyValues("monitoring.polling-interval-ms=20", "monitoring.initial-delay-ms=0",
                    "evidence.enabled=true", "evidence.polling-interval-ms=20", "evidence.initial-delay-ms=0",
                    "ai.analysis.polling-interval-ms=1000", "ai.analysis.initial-delay-ms=0");

    @EnableConfigurationProperties(AiProperties.class)
    static class Properties {
    }

    @Test
    void blockedAnalysisDelaysNeitherMonitoringNorEvidenceAndDoesNotOverlap() {
        CountDownLatch release = new CountDownLatch(1);
        when(analysis.runCycle()).thenAnswer(call -> {
            release.await();
            return Optional.empty();
        });

        runner.withPropertyValues("ai.enabled=true", "monitoring.enabled=true").run(context -> {
            verify(analysis, timeout(5000)).runCycle();
            verify(phase1, timeout(5000).atLeast(10)).runCycle();
            verify(evidence, timeout(5000).atLeast(10)).runCycle();
            verify(analysis, times(1)).runCycle();
            assertThat(context).hasSingleBean(TaskScheduler.class);
            release.countDown();
        });
    }

    @Test
    void failingCycleIsRunAgain() {
        when(analysis.runCycle()).thenThrow(new IllegalStateException("boom"));

        runner.withPropertyValues("ai.enabled=true", "monitoring.enabled=true")
                .run(context -> verify(analysis, timeout(10000).atLeast(2)).runCycle());
    }

    @Test
    void runsWithoutMonitoringAndDoesNotStartTheOtherSchedulers() {
        runner.withPropertyValues("ai.enabled=true", "monitoring.enabled=false").run(context -> {
            verify(analysis, timeout(5000)).runCycle();
            verify(evidence, never()).runCycle();
            verify(phase1, never()).runCycle();
        });
    }

    @Test
    void nothingRunsWhenDisabled() {
        runner.withPropertyValues("monitoring.enabled=true").run(context -> {
            assertThat(context).doesNotHaveBean(FailureAnalysisScheduler.class);
            verify(phase1, timeout(5000).atLeast(3)).runCycle();
            verify(analysis, never()).runCycle();
        });
    }

    @Test
    void stopsWithTheApplication() {
        runner.withPropertyValues("ai.enabled=true", "monitoring.enabled=true")
                .run(context -> assertThat(context.getBean(FailureAnalysisScheduler.class).isRunning()).isTrue());

        verify(analysis, timeout(5000).atLeastOnce()).stop();
    }
}
