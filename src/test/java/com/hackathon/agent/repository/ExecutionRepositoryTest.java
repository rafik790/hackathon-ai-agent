package com.hackathon.agent.repository;

import static com.hackathon.agent.support.TestEntities.NOW;
import static com.hackathon.agent.support.TestEntities.pipelineRun;
import static com.hackathon.agent.support.TestEntities.taskRun;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hackathon.agent.entity.PipelineRunEntity;
import com.hackathon.agent.entity.TaskRunEntity;
import com.hackathon.agent.enums.ExecutionStatus;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.ActiveProfiles;

@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class ExecutionRepositoryTest {

    @Autowired
    private PipelineRunRepository pipelineRuns;

    @Autowired
    private TaskRunRepository taskRuns;

    @Test
    void pipelineRunUidIsUnique() {
        pipelineRuns.saveAndFlush(pipelineRun("uid-1", "ci", "build", ExecutionStatus.SUCCEEDED, NOW, 1000L));

        assertThatThrownBy(() -> pipelineRuns.saveAndFlush(
                pipelineRun("uid-1", "ci", "build", ExecutionStatus.FAILED, NOW, 2000L)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void taskRunUidIsUnique() {
        PipelineRunEntity parent = pipelineRuns.saveAndFlush(
                pipelineRun("uid-1", "ci", "build", ExecutionStatus.SUCCEEDED, NOW, 1000L));
        taskRuns.saveAndFlush(taskRun("t-1", parent, "test", ExecutionStatus.SUCCEEDED, NOW, 100L));

        assertThatThrownBy(() -> taskRuns.saveAndFlush(taskRun("t-1", parent, "test", ExecutionStatus.FAILED, NOW, 1L)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void persistsUtcTimestampsWithoutShift() {
        PipelineRunEntity saved = pipelineRuns.saveAndFlush(
                pipelineRun("uid-1", "ci", "build", ExecutionStatus.SUCCEEDED, NOW, 1500L));

        PipelineRunEntity loaded = pipelineRuns.findByPipelineRunUid("uid-1").orElseThrow();

        assertThat(loaded.getId()).isEqualTo(saved.getId());
        assertThat(loaded.getStartTime()).isEqualTo(NOW);
        assertThat(loaded.getCompletionTime()).isEqualTo(NOW.plusMillis(1500));
    }

    @Test
    void findsTaskRunsOfPipelineRunByOwnerUidOrNameFallback() {
        PipelineRunEntity parent = pipelineRuns.save(pipelineRun("uid-1", "ci", "build", ExecutionStatus.FAILED, NOW, 1000L));
        PipelineRunEntity other = pipelineRuns.save(pipelineRun("uid-2", "ci", "build", ExecutionStatus.SUCCEEDED, NOW, 1000L));
        taskRuns.save(taskRun("t-2", parent, "test", ExecutionStatus.FAILED, NOW.plusSeconds(20), 10L));
        taskRuns.save(taskRun("t-1", parent, "clone", ExecutionStatus.SUCCEEDED, NOW, 10L));
        TaskRunEntity withoutOwner = taskRun("t-3", parent, "notify", ExecutionStatus.SUCCEEDED, NOW.plusSeconds(40), 10L);
        withoutOwner.setPipelineRunUid(null);
        taskRuns.save(withoutOwner);
        taskRuns.save(taskRun("t-4", other, "clone", ExecutionStatus.SUCCEEDED, NOW, 10L));
        taskRuns.flush();

        List<TaskRunEntity> tasks = taskRuns.findByPipelineRun("uid-1", "ci", parent.getPipelineRunName());

        assertThat(tasks).extracting(TaskRunEntity::getTaskName).containsExactly("clone", "test", "notify");
    }

    @Test
    void filtersAndPaginatesWithSpecifications() {
        for (int i = 0; i < 5; i++) {
            pipelineRuns.save(pipelineRun("ci-" + i, "ci", "build", ExecutionStatus.SUCCEEDED,
                    NOW.minus(i, ChronoUnit.HOURS), 1000L));
        }
        pipelineRuns.save(pipelineRun("ci-f", "ci", "build", ExecutionStatus.FAILED, NOW, 1000L));
        pipelineRuns.save(pipelineRun("st-1", "staging", "deploy", ExecutionStatus.SUCCEEDED, NOW, 1000L));
        pipelineRuns.flush();

        Page<PipelineRunEntity> page = pipelineRuns.findAll(
                ExecutionSpecifications.matching(new ExecutionFilter("ci", "build", ExecutionStatus.SUCCEEDED,
                        NOW.minus(3, ChronoUnit.HOURS), NOW.plusSeconds(1))),
                PageRequest.of(0, 2, Sort.by(Sort.Direction.DESC, "startTime")));

        assertThat(page.getTotalElements()).isEqualTo(4);
        assertThat(page.getTotalPages()).isEqualTo(2);
        assertThat(page.getContent()).extracting(PipelineRunEntity::getPipelineRunUid).containsExactly("ci-0", "ci-1");
    }
}
