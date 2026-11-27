package com.hackathon.agent.investigation.tool;

import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Helpers for reading untyped Tekton resources and building tool results.
 */
final class Resources {

    /** Parameter names whose values are never shown, whatever they contain. */
    private static final Pattern SENSITIVE_NAME = Pattern.compile(
            "(?i)pass(word|wd|phrase)?|secret|token|api[_-]?key|private[_-]?key|access[_-]?key|credential|auth|cert");

    static final String REDACTED = "[REDACTED]";

    private Resources() {
    }

    static Map<?, ?> map(Object value) {
        return value instanceof Map<?, ?> map ? map : Map.of();
    }

    static List<?> list(Object value) {
        return value instanceof List<?> list ? list : List.of();
    }

    static String text(Object value) {
        return value == null || value.toString().isBlank() ? null : value.toString();
    }

    static Map<?, ?> spec(GenericKubernetesResource resource) {
        return map(resource.getAdditionalProperties().get("spec"));
    }

    static Map<?, ?> status(GenericKubernetesResource resource) {
        return map(resource.getAdditionalProperties().get("status"));
    }

    static String label(GenericKubernetesResource resource, String key) {
        Map<String, String> labels = resource.getMetadata() == null ? null : resource.getMetadata().getLabels();
        return labels == null ? null : labels.get(key);
    }

    /** The {@code Succeeded} condition of a Tekton run: status, reason and message. */
    static Map<String, Object> succeededCondition(Map<?, ?> status) {
        Map<?, ?> condition = list(status.get("conditions")).stream()
                .map(Resources::map)
                .filter(c -> "Succeeded".equals(c.get("type")))
                .findFirst()
                .orElse(Map.of());
        return ordered("status", text(condition.get("status")), "reason", text(condition.get("reason")),
                "message", text(condition.get("message")));
    }

    /** Tekton parameters, with values of sensitive-looking parameters removed. */
    static List<Map<String, Object>> params(Object params, int max) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : list(params)) {
            Map<?, ?> param = map(item);
            String name = text(param.get("name"));
            if (name == null || result.size() >= max) {
                continue;
            }
            result.add(ordered("name", name, "value", isSensitive(name) ? REDACTED : param.get("value")));
        }
        return result;
    }

    static boolean isSensitive(String name) {
        return SENSITIVE_NAME.matcher(name).find();
    }

    /** A map in insertion order that leaves out null values. */
    static Map<String, Object> ordered(Object... keysAndValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keysAndValues.length; i += 2) {
            if (keysAndValues[i + 1] != null) {
                map.put((String) keysAndValues[i], keysAndValues[i + 1]);
            }
        }
        return map;
    }
}
