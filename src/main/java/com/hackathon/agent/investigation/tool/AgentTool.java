package com.hackathon.agent.investigation.tool;

import java.util.Map;

/**
 * A predefined, read-only tool the investigation agent may ask for. Every tool takes exactly a resource name and a
 * namespace, which the registry validates before the tool runs.
 */
public interface AgentTool {

    /** Name the model uses to select the tool. */
    String name();

    /** What the tool returns and when it helps; shown to the model. */
    String description();

    /** What {@code resourceName} means for this tool; shown to the model. */
    String resourceNameMeaning();

    /**
     * @return the result as plain data (maps, lists, strings, numbers); it is sanitized and bounded by the caller
     * @throws AgentToolException when the requested information cannot be read
     */
    Map<String, Object> execute(ToolArguments arguments);

    /** @param resourceName a validated Kubernetes name; @param namespace a monitored namespace */
    record ToolArguments(String resourceName, String namespace) {
    }
}
