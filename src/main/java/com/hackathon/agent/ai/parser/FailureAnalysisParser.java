package com.hackathon.agent.ai.parser;

import com.hackathon.agent.ai.model.EvidenceSource;
import com.hackathon.agent.ai.model.FailureAnalysisResult;
import com.hackathon.agent.ai.model.FailureCategory;
import com.hackathon.agent.ai.model.RecommendationPriority;
import com.hackathon.agent.ai.model.RecommendedAction;
import com.hackathon.agent.ai.model.Severity;
import com.hackathon.agent.ai.model.SupportingEvidence;
import com.hackathon.agent.evidence.sanitizer.LogSanitizer;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Validates the structure of an answer. Nothing in it is trusted: structure, enums and numbers are checked, text is
 * sanitized and bounded. Whether it agrees with the evidence is checked by {@link AnalysisEvidenceValidator}.
 */
@Component
public class FailureAnalysisParser {

    static final int MAX_SUMMARY = 2000;
    static final int MAX_ROOT_CAUSE = 4000;
    static final int MAX_TEXT = 1000;
    static final int MAX_ITEMS = 20;

    private final JsonMapper mapper = JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private final LogSanitizer sanitizer;

    public FailureAnalysisParser(LogSanitizer sanitizer) {
        this.sanitizer = sanitizer;
    }

    /**
     * @throws InvalidAnalysisException if the answer is not a complete, valid analysis
     */
    public FailureAnalysisResult parse(String answer) {
        JsonNode root = readObject(answer);
        List<String> problems = new ArrayList<>();

        String summary = text(root, "summary", "summary", MAX_SUMMARY, problems);
        FailureCategory category = enumValue(root, "failureCategory", "failureCategory", FailureCategory.class, problems);
        String rootCause = text(root, "rootCause", "rootCause", MAX_ROOT_CAUSE, problems);
        Severity severity = enumValue(root, "severity", "severity", Severity.class, problems);
        BigDecimal confidence = confidence(root, problems);
        List<String> facts = texts(root, "observedFacts", problems);
        List<String> hypotheses = texts(root, "hypotheses", problems);
        List<SupportingEvidence> evidence = evidence(root, problems);
        List<RecommendedAction> actions = actions(root, problems);
        List<String> preventive = texts(root, "preventiveRecommendations", problems);
        if (facts != null && facts.isEmpty()) {
            problems.add("observedFacts is empty");
        }
        if (actions != null && actions.isEmpty()) {
            problems.add("recommendedActions is empty");
        }
        if (!problems.isEmpty()) {
            throw new InvalidAnalysisException(problems);
        }
        return new FailureAnalysisResult(summary, category, rootCause, severity, confidence, facts, hypotheses,
                evidence, actions, preventive);
    }

    private JsonNode readObject(String answer) {
        JsonNode root;
        try {
            root = mapper.readTree(withoutCodeFence(answer == null ? "" : answer.strip()));
        } catch (JacksonException e) {
            throw new InvalidAnalysisException(List.of("the answer is not valid JSON"));
        }
        if (root == null || !root.isObject()) {
            throw new InvalidAnalysisException(List.of("the answer is not a JSON object"));
        }
        return root;
    }

    /** Some models wrap JSON in a Markdown code block although they were told not to. */
    private static String withoutCodeFence(String answer) {
        if (!answer.startsWith("```") || !answer.endsWith("```") || answer.length() < 6) {
            return answer;
        }
        int lineEnd = answer.indexOf('\n');
        return lineEnd < 0 || lineEnd > answer.length() - 3 ? answer : answer.substring(lineEnd + 1, answer.length() - 3).strip();
    }

    private String text(JsonNode node, String field, String path, int maxLength, List<String> problems) {
        JsonNode value = node.get(field);
        String text = value != null && value.isString() ? sanitizer.sanitizeMessage(value.stringValue().strip(), maxLength) : null;
        if (text == null) {
            problems.add(path + " is missing or not a text");
        }
        return text;
    }

    private static <E extends Enum<E>> E enumValue(JsonNode node, String field, String path, Class<E> type,
                                                   List<String> problems) {
        JsonNode value = node.get(field);
        if (value != null && value.isString()) {
            String name = value.stringValue().strip().toUpperCase(Locale.ROOT).replaceAll("[\\s-]+", "_");
            for (E constant : type.getEnumConstants()) {
                if (constant.name().equals(name)) {
                    return constant;
                }
            }
        }
        problems.add(path + " is missing or not an allowed value");
        return null;
    }

    private static BigDecimal confidence(JsonNode root, List<String> problems) {
        JsonNode value = root.get("confidence");
        if (value != null && value.isNumber()) {
            double number = value.doubleValue();
            if (number >= 0 && number <= 1) {
                return BigDecimal.valueOf(number).setScale(3, RoundingMode.HALF_UP);
            }
        }
        problems.add("confidence is missing or not a number between 0 and 1");
        return null;
    }

    private List<JsonNode> items(JsonNode root, String field, List<String> problems) {
        JsonNode value = root.get(field);
        if (value == null || !value.isArray()) {
            problems.add(field + " is missing or not a list");
            return null;
        }
        List<JsonNode> items = new ArrayList<>();
        value.forEach(items::add);
        return items.size() > MAX_ITEMS ? items.subList(0, MAX_ITEMS) : items;
    }

    private List<String> texts(JsonNode root, String field, List<String> problems) {
        List<JsonNode> items = items(root, field, problems);
        if (items == null) {
            return null;
        }
        List<String> texts = new ArrayList<>();
        for (JsonNode item : items) {
            String text = item.isString() ? sanitizer.sanitizeMessage(item.stringValue().strip(), MAX_TEXT) : null;
            if (text == null) {
                problems.add(field + " contains an entry that is not a text");
                return null;
            }
            texts.add(text);
        }
        return List.copyOf(texts);
    }

    private List<SupportingEvidence> evidence(JsonNode root, List<String> problems) {
        List<JsonNode> items = items(root, "supportingEvidence", problems);
        if (items == null) {
            return null;
        }
        List<SupportingEvidence> evidence = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            JsonNode item = items.get(i);
            String path = "supportingEvidence[" + i + "].";
            EvidenceSource source = enumValue(item, "source", path + "source", EvidenceSource.class, problems);
            String observation = text(item, "observation", path + "observation", MAX_TEXT, problems);
            JsonNode name = item.get("containerName");
            String container = name != null && name.isString() && !name.stringValue().isBlank() ? name.stringValue().strip() : null;
            evidence.add(new SupportingEvidence(source, container, observation));
        }
        return List.copyOf(evidence);
    }

    private List<RecommendedAction> actions(JsonNode root, List<String> problems) {
        List<JsonNode> items = items(root, "recommendedActions", problems);
        if (items == null) {
            return null;
        }
        List<RecommendedAction> actions = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            JsonNode item = items.get(i);
            String path = "recommendedActions[" + i + "].";
            actions.add(new RecommendedAction(
                    enumValue(item, "priority", path + "priority", RecommendationPriority.class, problems),
                    text(item, "action", path + "action", MAX_TEXT, problems),
                    text(item, "reason", path + "reason", MAX_TEXT, problems)));
        }
        return List.copyOf(actions);
    }
}
