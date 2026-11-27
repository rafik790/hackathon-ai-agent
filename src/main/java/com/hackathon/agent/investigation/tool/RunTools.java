package com.hackathon.agent.investigation.tool;

import static com.hackathon.agent.investigation.tool.Resources.label;
import static com.hackathon.agent.investigation.tool.Resources.list;
import static com.hackathon.agent.investigation.tool.Resources.map;
import static com.hackathon.agent.investigation.tool.Resources.ordered;
import static com.hackathon.agent.investigation.tool.Resources.params;
import static com.hackathon.agent.investigation.tool.Resources.spec;
import static com.hackathon.agent.investigation.tool.Resources.status;
import static com.hackathon.agent.investigation.tool.Resources.succeededCondition;
import static com.hackathon.agent.investigation.tool.Resources.text;

import com.hackathon.agent.entity.PipelineRunEntity;
import com.hackathon.agent.entity.TaskRunEntity;
import com.hackathon.agent.enums.ExecutionStatus;
import com.hackathon.agent.investigation.repository.AgentPipelineRunLookup;
import com.hackathon.agent.repository.TaskRunRepository;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Tools about Tekton runs: PipelineRun details, TaskRun details and the previous successful PipelineRun.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnBooleanProperty(name = "agent.investigation.enabled")
public class RunTools {

    static final int MAX_PARAMS = 30;
    static final int MAX_TASKS = 30;
    /** Parameters that identify what was built and deployed. */
    private static final Pattern VERSION_PARAM = Pattern.compile("(?i)revision|commit|sha|ref|branch|tag|image|version|url");

    @Bean
    AgentTool getPipelineRunDetails(AgentKubernetesReader reader, AgentPipelineRunLookup pipelineRuns, TaskRunRepository taskRuns) {
        return new Tool("get_pipelinerun_details",
                "Status, pipeline, parameters (sensitive values removed), timestamps and the status of every task of a PipelineRun, including the failed ones.",
                "PipelineRun name",
                arguments -> {
                    Map<String, Object> result = reader.pipelineRun(arguments.namespace(), arguments.resourceName())
                            .map(RunTools::pipelineRun)
                            .orElseGet(() -> ordered("note", "The PipelineRun no longer exists in the cluster; only stored data is shown."));
                    PipelineRunEntity stored = pipelineRuns
                            .findFirstByNamespaceAndPipelineRunNameOrderByIdDesc(arguments.namespace(), arguments.resourceName())
                            .orElse(null);
                    if (stored == null && result.containsKey("note")) {
                        throw new AgentToolException("PipelineRun " + arguments.resourceName() + " not found");
                    }
                    if (stored != null) {
                        result.putIfAbsent("pipeline", stored.getPipelineName());
                        result.putIfAbsent("startTime", stored.getStartTime());
                        result.putIfAbsent("completionTime", stored.getCompletionTime());
                        result.put("tasks", taskRuns.findByPipelineRun(stored.getPipelineRunUid(), stored.getNamespace(),
                                stored.getPipelineRunName()).stream().limit(MAX_TASKS).map(RunTools::storedTask).toList());
                    }
                    return result;
                });
    }

    @Bean
    AgentTool getTaskRunDetails(AgentKubernetesReader reader) {
        return new Tool("get_taskrun_details",
                "Status, task, PipelineRun, Pod name, service account, failure reason, timestamps and the outcome of every step of a TaskRun.",
                "TaskRun name",
                arguments -> reader.taskRun(arguments.namespace(), arguments.resourceName())
                        .map(RunTools::taskRun)
                        .orElseThrow(() -> new AgentToolException("TaskRun " + arguments.resourceName() + " not found in the cluster")));
    }

    @Bean
    AgentTool getPreviousSuccessfulPipelineRun(AgentKubernetesReader reader, AgentPipelineRunLookup pipelineRuns,
                                               TaskRunRepository taskRuns) {
        return new Tool("get_previous_successful_pipelinerun",
                "The most recent successful run of a pipeline, its task statuses and, if still in the cluster, the parameters that identify what was built (revision, image), for comparison with the failed run.",
                "Pipeline name (not the PipelineRun name)",
                arguments -> {
                    PipelineRunEntity run = pipelineRuns.findFirstByNamespaceAndPipelineNameAndStatusOrderByCompletionTimeDescIdDesc(
                                    arguments.namespace(), arguments.resourceName(), ExecutionStatus.SUCCEEDED)
                            .orElseThrow(() -> new AgentToolException("No successful run of pipeline " + arguments.resourceName() + " is stored"));
                    Map<String, Object> result = ordered("pipelineRun", run.getPipelineRunName(), "pipeline", run.getPipelineName(),
                            "startTime", run.getStartTime(), "completionTime", run.getCompletionTime(), "durationMs", run.getDurationMs());
                    result.put("tasks", taskRuns.findByPipelineRun(run.getPipelineRunUid(), run.getNamespace(), run.getPipelineRunName())
                            .stream().limit(MAX_TASKS).map(RunTools::storedTask).toList());
                    try {
                        reader.pipelineRun(run.getNamespace(), run.getPipelineRunName()).ifPresentOrElse(
                                resource -> result.put("versionParameters", params(spec(resource).get("params"), MAX_PARAMS).stream()
                                        .filter(p -> VERSION_PARAM.matcher((String) p.get("name")).find()).toList()),
                                () -> result.put("note", "The run no longer exists in the cluster; its parameters are unknown."));
                    } catch (AgentToolException e) {
                        result.put("note", e.getMessage());
                    }
                    return result;
                });
    }

    private static Map<String, Object> pipelineRun(GenericKubernetesResource resource) {
        Map<?, ?> spec = spec(resource);
        Map<?, ?> status = status(resource);
        Map<String, Object> result = ordered(
                "name", resource.getMetadata().getName(),
                "pipeline", firstText(map(spec.get("pipelineRef")).get("name"), label(resource, "tekton.dev/pipeline")),
                "status", succeededCondition(status),
                "startTime", text(status.get("startTime")),
                "completionTime", text(status.get("completionTime")),
                "serviceAccountName", firstText(map(spec.get("taskRunTemplate")).get("serviceAccountName"), spec.get("serviceAccountName")));
        result.put("params", params(spec.get("params"), MAX_PARAMS));
        List<Map<String, Object>> children = new ArrayList<>();
        for (Object child : list(status.get("childReferences"))) {
            Map<?, ?> reference = map(child);
            if (children.size() < MAX_TASKS) {
                children.add(ordered("kind", text(reference.get("kind")), "name", text(reference.get("name")),
                        "pipelineTask", text(reference.get("pipelineTaskName"))));
            }
        }
        result.put("childRuns", children);
        return result;
    }

    private static Map<String, Object> taskRun(GenericKubernetesResource resource) {
        Map<?, ?> spec = spec(resource);
        Map<?, ?> status = status(resource);
        Map<String, Object> result = ordered(
                "name", resource.getMetadata().getName(),
                "task", firstText(map(spec.get("taskRef")).get("name"), label(resource, "tekton.dev/task")),
                "pipelineRun", label(resource, "tekton.dev/pipelineRun"),
                "pipelineTask", label(resource, "tekton.dev/pipelineTask"),
                "status", succeededCondition(status),
                "podName", text(status.get("podName")),
                "serviceAccountName", text(spec.get("serviceAccountName")),
                "startTime", text(status.get("startTime")),
                "completionTime", text(status.get("completionTime")),
                "retries", list(status.get("retriesStatus")).size());
        List<Map<String, Object>> steps = new ArrayList<>();
        for (Object item : list(status.get("steps"))) {
            Map<?, ?> step = map(item);
            Map<?, ?> terminated = map(step.get("terminated"));
            Map<?, ?> waiting = map(step.get("waiting"));
            steps.add(ordered("name", text(step.get("name")), "container", text(step.get("container")),
                    "exitCode", terminated.get("exitCode"),
                    "reason", firstText(terminated.get("reason"), waiting.get("reason")),
                    "terminationReason", text(step.get("terminationReason")),
                    "message", firstText(terminated.get("message"), waiting.get("message"))));
        }
        result.put("steps", steps);
        return result;
    }

    private static Map<String, Object> storedTask(TaskRunEntity task) {
        return ordered("taskRun", task.getTaskRunName(), "task", task.getTaskName(), "status", task.getStatus().name(),
                "failureReason", task.getFailureReason(), "failureMessage", task.getFailureMessage());
    }

    private static String firstText(Object first, Object second) {
        return text(first) != null ? text(first) : text(second);
    }

    /** A tool whose behaviour is a function. */
    record Tool(String name, String description, String resourceNameMeaning,
                java.util.function.Function<ToolArguments, Map<String, Object>> body) implements AgentTool {

        @Override
        public Map<String, Object> execute(ToolArguments arguments) {
            return body.apply(arguments);
        }
    }
}
