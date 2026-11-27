package com.hackathon.agent.investigation.tool;

import static com.hackathon.agent.investigation.tool.Resources.list;
import static com.hackathon.agent.investigation.tool.Resources.map;
import static com.hackathon.agent.investigation.tool.Resources.ordered;
import static com.hackathon.agent.investigation.tool.Resources.status;
import static com.hackathon.agent.investigation.tool.Resources.text;

import com.hackathon.agent.evidence.client.KubernetesEvidenceClient;
import com.hackathon.agent.evidence.entity.ContainerEvidenceEntity;
import com.hackathon.agent.evidence.entity.ContainerLogEntity;
import com.hackathon.agent.evidence.entity.FailureEvidenceEntity;
import com.hackathon.agent.evidence.model.LogStatus;
import com.hackathon.agent.evidence.repository.ContainerEvidenceRepository;
import com.hackathon.agent.evidence.repository.ContainerLogRepository;
import com.hackathon.agent.evidence.repository.FailureEvidenceRepository;
import com.hackathon.agent.evidence.sanitizer.LogSanitizer;
import com.hackathon.agent.evidence.sanitizer.TailBuffer;
import com.hackathon.agent.investigation.AgentInvestigationProperties;
import com.hackathon.agent.investigation.repository.AgentTaskRunLookup;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Logs of the failed containers of a TaskRun. Uses the logs that evidence collection already stored and redacted;
 * only when there are none, and evidence collection is active, the end of the logs is read from the cluster.
 */
@Component
@ConditionalOnBooleanProperty(name = "agent.investigation.enabled")
public class LogTool implements AgentTool {

    static final int MAX_CONTAINERS = 3;
    static final int LIVE_TAIL_LINES = 200;

    private final AgentTaskRunLookup taskRuns;
    private final FailureEvidenceRepository evidence;
    private final ContainerEvidenceRepository containers;
    private final ContainerLogRepository logs;
    private final AgentKubernetesReader reader;
    private final ObjectProvider<KubernetesEvidenceClient> liveLogs;
    private final LogSanitizer sanitizer;
    private final AgentInvestigationProperties properties;

    public LogTool(AgentTaskRunLookup taskRuns, FailureEvidenceRepository evidence, ContainerEvidenceRepository containers,
                   ContainerLogRepository logs, AgentKubernetesReader reader, ObjectProvider<KubernetesEvidenceClient> liveLogs,
                   LogSanitizer sanitizer, AgentInvestigationProperties properties) {
        this.taskRuns = taskRuns;
        this.evidence = evidence;
        this.containers = containers;
        this.logs = logs;
        this.reader = reader;
        this.liveLogs = liveLogs;
        this.sanitizer = sanitizer;
        this.properties = properties;
    }

    @Override
    public String name() {
        return "get_taskrun_logs";
    }

    @Override
    public String description() {
        return "The end of the logs of the failed containers (steps) of a TaskRun, with container name, exit code and termination reason. "
                + "Use it for build, test, script and permission errors that a step reports.";
    }

    @Override
    public String resourceNameMeaning() {
        return "TaskRun name";
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> execute(ToolArguments arguments) {
        List<Map<String, Object>> stored = taskRuns.findFirstByNamespaceAndTaskRunNameOrderByIdDesc(arguments.namespace(), arguments.resourceName())
                .map(taskRun -> storedLogs(taskRun.getId()))
                .orElse(List.of());
        Map<String, Object> result = ordered("taskRun", arguments.resourceName());
        if (!stored.isEmpty()) {
            result.put("source", "logs stored by evidence collection");
            result.put("containers", stored);
            return result;
        }
        result.put("source", "cluster");
        result.put("containers", liveLogs(arguments));
        return result;
    }

    private List<Map<String, Object>> storedLogs(Long taskRunId) {
        List<FailureEvidenceEntity> attempts = evidence.findByTaskRunIdOrderByAttempt(taskRunId);
        if (attempts.isEmpty()) {
            return List.of();
        }
        // The last attempt decided the outcome.
        FailureEvidenceEntity last = attempts.getLast();
        List<ContainerEvidenceEntity> failed = containers.findByEvidenceId(last.getId()).stream()
                .filter(c -> c.isPrimaryFailure() || (c.getExitCode() != null && c.getExitCode() != 0))
                .sorted(Comparator.comparing((ContainerEvidenceEntity c) -> !c.isPrimaryFailure())
                        .thenComparing(ContainerEvidenceEntity::getOrdinal, Comparator.nullsLast(Comparator.naturalOrder())))
                .limit(MAX_CONTAINERS)
                .toList();
        int budget = properties.maxLogChars() / Math.max(1, failed.size());
        List<Map<String, Object>> result = new ArrayList<>();
        for (ContainerEvidenceEntity container : failed) {
            Map<String, Object> entry = ordered("container", container.getContainerName(), "exitCode", container.getExitCode(),
                    "reason", container.getReason(), "terminationReason", container.getTektonTerminationReason(),
                    "message", sanitizer.sanitizeMessage(container.getMessage(), 500));
            ContainerLogEntity log = logs.findByContainerEvidenceId(container.getId()).stream()
                    .filter(l -> !l.isPrevious()).findFirst().orElse(null);
            if (log == null || log.getStatus() != LogStatus.COLLECTED || log.getContent() == null) {
                entry.put("log", "not available" + (log == null ? "" : " (" + log.getStatus() + ")"));
            } else {
                entry.put("log", tail(sanitizer.sanitizeMessage(log.getContent(), Integer.MAX_VALUE), budget));
                entry.put("truncated", log.isTruncated() || log.getContent().length() > budget);
            }
            result.add(entry);
        }
        return result;
    }

    private List<Map<String, Object>> liveLogs(ToolArguments arguments) {
        GenericKubernetesResource taskRun = reader.taskRun(arguments.namespace(), arguments.resourceName())
                .orElseThrow(() -> new AgentToolException("TaskRun " + arguments.resourceName() + " not found"));
        String pod = text(status(taskRun).get("podName"));
        if (pod == null) {
            throw new AgentToolException("TaskRun " + arguments.resourceName() + " has no Pod, so there are no logs");
        }
        List<Map<?, ?>> failed = list(status(taskRun).get("steps")).stream().map(Resources::map)
                .filter(step -> map(step.get("terminated")).get("exitCode") instanceof Number code && code.intValue() != 0)
                .limit(MAX_CONTAINERS)
                .toList();
        KubernetesEvidenceClient client = liveLogs.getIfAvailable();
        int budget = properties.maxLogChars() / Math.max(1, failed.size());
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<?, ?> step : failed) {
            String container = text(step.get("container"));
            Map<?, ?> terminated = map(step.get("terminated"));
            Map<String, Object> entry = ordered("container", container, "exitCode", terminated.get("exitCode"),
                    "reason", text(terminated.get("reason")), "terminationReason", text(step.get("terminationReason")));
            entry.put("log", client == null || container == null ? "not available: evidence collection is disabled"
                    : readLog(client, arguments.namespace(), pod, container, budget));
            result.add(entry);
        }
        return result;
    }

    private String readLog(KubernetesEvidenceClient client, String namespace, String pod, String container, int budget) {
        TailBuffer tail = new TailBuffer(budget + LogSanitizer.CONTEXT_BYTES);
        try (InputStream stream = client.openLog(namespace, pod, container, false, LIVE_TAIL_LINES)) {
            tail.readFrom(stream);
        } catch (IOException | RuntimeException e) {
            return "not available: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
        return sanitizer.sanitizeLog(tail.toByteArray(), tail.overflowed(), budget).content();
    }

    /** The end of a log, starting at a line. */
    static String tail(String text, int maxChars) {
        if (text.length() <= maxChars) {
            return text;
        }
        String end = text.substring(text.length() - maxChars);
        int line = end.indexOf('\n');
        return line >= 0 && line < maxChars / 10 ? end.substring(line + 1) : end;
    }
}
