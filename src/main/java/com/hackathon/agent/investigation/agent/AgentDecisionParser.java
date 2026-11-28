package com.hackathon.agent.investigation.agent;

import com.hackathon.agent.ai.model.FailureCategory;
import com.hackathon.agent.investigation.agent.AgentDecision.Action;
import com.hackathon.agent.investigation.agent.AgentDecision.ProposedEvidence;
import com.hackathon.agent.investigation.agent.AgentDecision.ProposedReport;
import com.hackathon.agent.investigation.agent.AgentDecision.ProposedStep;
import com.hackathon.agent.investigation.agent.AgentDecision.ProposedTeamCheck;
import com.hackathon.agent.investigation.model.AgentReport.OwnershipConfidence;
import com.hackathon.agent.investigation.model.AgentReport.Team;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads the decision of the model and checks its structure. Whether a requested tool and its arguments are allowed
 * is decided by the tool registry, and whether a report is supported by {@link AgentReportValidator}.
 */
public class AgentDecisionParser {

    static final int MAX_LIST = 10;

    private final JsonMapper mapper = JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    /** JSON schema of a decision, for providers with structured output. All fields are required. */
    public static Map<String, Object> schema() {
        Map<String, Object> text = Map.of("type", "string");
        Map<String, Object> optionalText = Map.of("type", List.of("string", "null"));
        Map<String, Object> texts = Map.of("type", "array", "items", text);
        Map<String, Object> team = Map.of("type", "string", "enum", Arrays.stream(Team.values()).map(Enum::name).toList());
        Map<String, Object> steps = Map.of("type", "array", "items", object(ordered(
                "action", text,
                "command", optionalText)));
        Map<String, Object> report = object(ordered(
                "summary", text,
                "rootCause", text,
                "failureCategory", Map.of("type", "string", "enum", Arrays.stream(FailureCategory.values()).map(Enum::name).toList()),
                "confidence", Map.of("type", "number"),
                "evidence", Map.of("type", "array", "items", object(ordered(
                        "step", Map.of("type", "integer"),
                        "observation", text))),
                "recommendedActions", texts,
                "title", text,
                "ownership", object(ordered(
                        "team", team,
                        "confidence", Map.of("type", "string", "enum", Arrays.stream(OwnershipConfidence.values()).map(Enum::name).toList()),
                        "reason", text)),
                "resolutionSteps", steps,
                "otherTeamChecks", Map.of("type", "array", "items", object(ordered(
                        "team", team,
                        "request", text))),
                "verificationSteps", steps,
                "expectedOutcome", text,
                "missingInformation", texts,
                "nextDiagnosticAction", optionalText));
        return object(ordered(
                "reasoning", text,
                "action", Map.of("type", "string", "enum", List.of(Action.CALL_TOOL.name(), Action.FINAL_REPORT.name())),
                "toolName", optionalText,
                "resourceName", optionalText,
                "namespace", optionalText,
                "report", report));
    }

    /**
     * @throws InvalidDecisionException if the answer is not a decision
     */
    public AgentDecision parse(String answer) {
        JsonNode root;
        try {
            root = mapper.readTree(withoutCodeFence(answer == null ? "" : answer.strip()));
        } catch (JacksonException e) {
            throw new InvalidDecisionException("the answer is not valid JSON");
        }
        if (root == null || !root.isObject()) {
            throw new InvalidDecisionException("the answer is not a JSON object");
        }
        Action action = action(text(root, "action"));
        String reasoning = text(root, "reasoning");
        if (action == Action.CALL_TOOL) {
            String tool = text(root, "toolName");
            if (tool == null) {
                throw new InvalidDecisionException("toolName is required for CALL_TOOL");
            }
            return new AgentDecision(reasoning, action, tool, text(root, "resourceName"), text(root, "namespace"), null);
        }
        return new AgentDecision(reasoning, action, null, null, null, report(root.get("report")));
    }

    private static Action action(String value) {
        if (value != null) {
            try {
                return Action.valueOf(value.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                // reported below
            }
        }
        throw new InvalidDecisionException("action must be CALL_TOOL or FINAL_REPORT");
    }

    private static ProposedReport report(JsonNode node) {
        if (node == null || !node.isObject()) {
            throw new InvalidDecisionException("report is required for FINAL_REPORT");
        }
        String rootCause = text(node, "rootCause");
        if (rootCause == null) {
            throw new InvalidDecisionException("report.rootCause is required");
        }
        JsonNode confidence = node.get("confidence");
        if (confidence == null || !confidence.isNumber()) {
            throw new InvalidDecisionException("report.confidence must be a number");
        }
        List<ProposedEvidence> evidence = new ArrayList<>();
        JsonNode items = node.get("evidence");
        if (items != null && items.isArray()) {
            for (JsonNode item : items) {
                JsonNode step = item.get("step");
                String observation = text(item, "observation");
                if (step != null && step.canConvertToInt() && observation != null && evidence.size() < MAX_LIST) {
                    evidence.add(new ProposedEvidence(step.intValue(), observation));
                }
            }
        }
        JsonNode ownership = node.get("ownership");
        boolean hasOwnership = ownership != null && ownership.isObject();
        List<ProposedTeamCheck> checks = new ArrayList<>();
        for (JsonNode item : array(node, "otherTeamChecks")) {
            String request = text(item, "request");
            if (request != null && checks.size() < MAX_LIST) {
                checks.add(new ProposedTeamCheck(text(item, "team"), request));
            }
        }
        return new ProposedReport(text(node, "summary"), rootCause, text(node, "failureCategory"),
                confidence.decimalValue(), evidence, texts(node, "recommendedActions"),
                text(node, "title"),
                hasOwnership ? text(ownership, "team") : null,
                hasOwnership ? text(ownership, "confidence") : null,
                hasOwnership ? text(ownership, "reason") : null,
                steps(node, "resolutionSteps"), checks, steps(node, "verificationSteps"),
                text(node, "expectedOutcome"), texts(node, "missingInformation"), text(node, "nextDiagnosticAction"));
    }

    /** Missing or malformed lists are empty: the fields are optional for the parser, the validator fills gaps. */
    private static List<JsonNode> array(JsonNode node, String field) {
        JsonNode value = node.get(field);
        List<JsonNode> items = new ArrayList<>();
        if (value != null && value.isArray()) {
            value.forEach(item -> {
                if (item.isObject()) {
                    items.add(item);
                }
            });
        }
        return items;
    }

    private static List<String> texts(JsonNode node, String field) {
        List<String> texts = new ArrayList<>();
        JsonNode value = node.get(field);
        if (value != null && value.isArray()) {
            for (JsonNode item : value) {
                if (item.isString() && !item.stringValue().isBlank() && texts.size() < MAX_LIST) {
                    texts.add(item.stringValue().strip());
                }
            }
        }
        return texts;
    }

    private static List<ProposedStep> steps(JsonNode node, String field) {
        List<ProposedStep> steps = new ArrayList<>();
        for (JsonNode item : array(node, field)) {
            String action = text(item, "action");
            if (action != null && steps.size() < MAX_LIST) {
                steps.add(new ProposedStep(action, command(item.get("command"))));
            }
        }
        return steps;
    }

    /** Commands keep their indentation (YAML) and only lose surrounding blank lines. */
    private static String command(JsonNode value) {
        if (value == null || !value.isString() || value.stringValue().isBlank()) {
            return null;
        }
        return value.stringValue().replaceAll("^\\s*\\n|\\s+$", "");
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isString() && !value.stringValue().isBlank() ? value.stringValue().strip() : null;
    }

    /** Some models wrap JSON in a Markdown code block although they were told not to. */
    private static String withoutCodeFence(String answer) {
        if (!answer.startsWith("```") || !answer.endsWith("```") || answer.length() < 6) {
            return answer;
        }
        int lineEnd = answer.indexOf('\n');
        return lineEnd < 0 || lineEnd > answer.length() - 3 ? answer : answer.substring(lineEnd + 1, answer.length() - 3).strip();
    }

    private static Map<String, Object> object(Map<String, Object> properties) {
        Map<String, Object> object = new LinkedHashMap<>();
        object.put("type", "object");
        object.put("properties", properties);
        object.put("required", List.copyOf(properties.keySet()));
        object.put("additionalProperties", false);
        return object;
    }

    private static Map<String, Object> ordered(Object... keysAndValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            map.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return map;
    }

    /** The answer is not a usable decision; the message is shown to the model so it can correct itself. */
    public static class InvalidDecisionException extends RuntimeException {

        public InvalidDecisionException(String message) {
            super(message);
        }
    }
}
