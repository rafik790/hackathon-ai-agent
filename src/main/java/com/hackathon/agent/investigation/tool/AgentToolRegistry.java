package com.hackathon.agent.investigation.tool;

import com.hackathon.agent.config.MonitoringProperties;
import com.hackathon.agent.evidence.sanitizer.LogSanitizer;
import com.hackathon.agent.investigation.AgentInvestigationProperties;
import com.hackathon.agent.investigation.tool.AgentTool.ToolArguments;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * The fixed set of tools the agent may use, and the only way to run them. Every request is validated: the tool must
 * be registered, the resource name must be a valid Kubernetes name and the namespace one of the monitored ones.
 * Results are redacted and bounded before anyone sees them.
 */
@Component
@ConditionalOnBooleanProperty(name = "agent.investigation.enabled")
public class AgentToolRegistry {

    /** RFC 1123 subdomain, the format of most Kubernetes object names. */
    private static final Pattern RESOURCE_NAME = Pattern.compile("[a-z0-9]([-a-z0-9.]{0,251}[a-z0-9])?");
    private static final Pattern NAMESPACE = Pattern.compile("[a-z0-9]([-a-z0-9]{0,61}[a-z0-9])?");
    static final String TRUNCATED = "...[truncated]";

    private final Map<String, AgentTool> tools = new LinkedHashMap<>();
    private final MonitoringProperties monitoring;
    private final LogSanitizer sanitizer;
    private final AgentInvestigationProperties properties;
    private final JsonMapper mapper = JsonMapper.builder().build();

    public AgentToolRegistry(Collection<AgentTool> tools, MonitoringProperties monitoring, LogSanitizer sanitizer,
                             AgentInvestigationProperties properties) {

        tools.stream().sorted(Comparator.comparing(AgentTool::name)).forEach(tool -> {
            if (this.tools.put(tool.name(), tool) != null) {
                throw new IllegalStateException("Duplicate agent tool " + tool.name());
            }
        });
        this.monitoring = monitoring;
        this.sanitizer = sanitizer;
        this.properties = properties;
    }

    public List<AgentTool> tools() {
        return List.copyOf(tools.values());
    }

    public Optional<AgentTool> find(String name) {
        return Optional.ofNullable(name == null ? null : tools.get(name));
    }

    /**
     * @return the reason why the arguments are rejected, or empty when they are valid
     */
    public Optional<String> validate(String resourceName, String namespace) {
        if (resourceName == null || !RESOURCE_NAME.matcher(resourceName).matches()) {
            return Optional.of("resourceName must be a Kubernetes object name (lower case letters, digits, '-' and '.')");
        }
        if (namespace == null || !NAMESPACE.matcher(namespace).matches()) {
            return Optional.of("namespace must be a Kubernetes namespace name");
        }
        if (!monitoring.namespaces().contains(namespace)) {
            return Optional.of("namespace " + namespace + " is not monitored; allowed: " + String.join(", ", monitoring.namespaces()));
        }
        return Optional.empty();
    }

    /**
     * Runs a registered tool with validated arguments.
     *
     * @return the sanitized result as JSON, at most {@code maxToolOutputChars} long
     * @throws AgentToolException if the tool fails
     */
    public String execute(AgentTool tool, ToolArguments arguments) {
        return format(tool.execute(arguments));
    }

    /** Redacts every text of a result and bounds its size. */
    public String format(Object result) {
        String json = mapper.writeValueAsString(sanitize(result));
        int max = properties.maxToolOutputChars();
        return json.length() <= max ? json : json.substring(0, max - TRUNCATED.length()) + TRUNCATED;
    }

    private Object sanitize(Object value) {
        if (value instanceof String text) {
            return sanitizer.sanitizeMessage(text, properties.maxToolOutputChars());
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, item) -> result.put(String.valueOf(key), sanitize(item)));
            return result;
        }
        if (value instanceof Collection<?> collection) {
            List<Object> result = new ArrayList<>();
            collection.forEach(item -> result.add(sanitize(item)));
            return result;
        }
        return value == null || value instanceof Number || value instanceof Boolean ? value : sanitize(value.toString());
    }
}
