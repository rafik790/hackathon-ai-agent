package com.hackathon.agent.investigation.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hackathon.agent.evidence.sanitizer.LogSanitizer;
import com.hackathon.agent.investigation.AgentInvestigationProperties;
import com.hackathon.agent.investigation.tool.AgentTool.ToolArguments;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class AgentToolRegistryTest {

    private final AgentTool echo = new RunTools.Tool("echo", "returns its input", "anything",
            arguments -> Map.of("resource", arguments.resourceName()));
    private final AgentToolRegistry registry = new AgentToolRegistry(List.of(echo), AgentToolsTest.monitoring(), new LogSanitizer(),
            new AgentInvestigationProperties(true, 8, 10, 300, 600, 8000, 60000, 2000, 900));

    @Test
    void findsOnlyRegisteredTools() {
        assertThat(registry.find("echo")).contains(echo);
        assertThat(registry.find("ECHO")).isEmpty();
        assertThat(registry.find("kubectl")).isEmpty();
        assertThat(registry.find(null)).isEmpty();
    }

    @Test
    void duplicateToolNamesAreAConfigurationError() {
        assertThatThrownBy(() -> new AgentToolRegistry(List.of(echo, echo), AgentToolsTest.monitoring(), new LogSanitizer(),
                new AgentInvestigationProperties(true, 8, 10, 300, 600, 8000, 60000, 2000, 900)))
                .isInstanceOf(IllegalStateException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"build-1", "a", "demo-app.v2", "x1"})
    void acceptsKubernetesNamesInMonitoredNamespaces(String name) {
        assertThat(registry.validate(name, "ci")).isEmpty();
        assertThat(registry.validate(name, "staging")).isEmpty();
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', nullValues = "null", value = {
            "Build                | ci          | resourceName must be a Kubernetes object name",
            "a b                  | ci          | resourceName must be a Kubernetes object name",
            "../etc/passwd        | ci          | resourceName must be a Kubernetes object name",
            "x;rm -rf /           | ci          | resourceName must be a Kubernetes object name",
            "-x                   | ci          | resourceName must be a Kubernetes object name",
            "null                 | ci          | resourceName must be a Kubernetes object name",
            "x                    | null        | namespace must be a Kubernetes namespace name",
            "x                    | Ci          | namespace must be a Kubernetes namespace name",
            "x                    | kube-system | namespace kube-system is not monitored"})
    void rejectsInvalidArguments(String name, String namespace, String reason) {
        assertThat(registry.validate(name, namespace)).hasValueSatisfying(r -> assertThat(r).startsWith(reason));
    }

    @Test
    void rejectsOverlongNames() {
        assertThat(registry.validate("a".repeat(254), "ci")).isPresent();
        assertThat(registry.validate("a".repeat(253), "ci")).isEmpty();
    }

    @Test
    void resultsAreRedactedAndBounded() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("message", "failed with password=hunter2secret");
        result.put("nested", List.of(Map.of("token", "ghp_abcdefghijklmnopqrstuvwxyz0123456789")));
        result.put("count", 3);
        result.put("long", "x".repeat(2000));

        String json = registry.format(result);

        assertThat(json).hasSize(600).endsWith(AgentToolRegistry.TRUNCATED).contains("[REDACTED]", "\"count\":3")
                .doesNotContain("hunter2secret").doesNotContain("ghp_abcdefghijklmnopqrstuvwxyz0123456789");
    }

    @Test
    void executesTheTool() {
        assertThat(registry.execute(echo, new ToolArguments("build-1", "ci"))).isEqualTo("{\"resource\":\"build-1\"}");
    }
}
