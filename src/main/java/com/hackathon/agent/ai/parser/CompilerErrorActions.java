package com.hackathon.agent.ai.parser;

import com.hackathon.agent.ai.model.FailureContext;
import com.hackathon.agent.ai.model.RecommendationPriority;
import com.hackathon.agent.ai.model.RecommendedAction;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Recommended actions for compiler errors, most important first: correct the first error and those it did not cause,
 * check the correction, prevent it from happening again.
 * <p>
 * An action of the model is used if it corrects one of the reported errors, i.e. names its file and line and nothing
 * that contradicts the evidence. Everything else it proposed cannot follow from the validated cause and is left out.
 */
final class CompilerErrorActions {

    /** Build tool and tasks, only those that just build on the own machine. */
    private static final Pattern BUILD_COMMAND = Pattern.compile("(?<![\\w./-])(?<tool>\\./gradlew|\\./mvnw|gradle|mvn)(?<arguments>(?:[ \\t]{1,20}(?:-[\\w.=:/-]{1,100}"
            + "|clean|build|assemble|bootJar|bootWar|jar|war|classes|testClasses|compileJava|compileKotlin|compile|test-compile|package|check|test|verify)){1,10})"
            + "[ \\t]{0,20}(?=$|[\"'&;|)])", Pattern.MULTILINE);
    private static final Pattern IMAGE_BUILD = Pattern.compile("(?im)^(?:\\[\\d+/\\d+] )?STEP \\d+|building image|building at STEP|kaniko|buildkit");
    private static final Pattern SOURCE_FILE = Pattern.compile("[\\w$-]+\\.(?:java|kts?|scala|groovy|c|cc|cpp|cxx|h|hpp|cs|go|rs|swift|tsx?)\\b");
    private static final Pattern LINES = Pattern.compile("(?i)\\bline (\\d{1,7})\\b|\\blines (\\d{1,7}(?:(?:,| ?- ?| ?– ?| to | and | & ){1,2} ?\\d{1,7}){0,10})"
            + "|\\.[a-z]{1,6}:(\\d{1,7})\\b");

    private CompilerErrorActions() {
    }

    /** @param errors errors in the order in which they were reported, at least one */
    static List<RecommendedAction> derive(List<CompilerError> errors, List<RecommendedAction> proposed, EvidenceMatcher matcher,
                                          FailureContext context) {
        CompilerError primary = errors.getFirst();
        List<RecommendedAction> actions = new ArrayList<>();
        actions.add(correction(primary, proposed, matcher).orElseGet(() -> new RecommendedAction(RecommendationPriority.HIGH,
                primary.correction(), "The log of " + primary.container() + " reports: " + primary.logLine())));
        for (CompilerError error : errors) {
            if (error != primary && !error.isConsequenceOf(primary)) {
                correction(error, proposed, matcher).filter(action -> !actions.contains(action)).ifPresent(actions::add);
            }
        }
        while (actions.size() > FailureAnalysisParser.MAX_ITEMS - 2) {
            actions.removeLast();
        }
        String log = context.logs().stream().filter(l -> l.content() != null && l.containerName().equals(primary.container()))
                .map(FailureContext.ContainerLog::content).collect(Collectors.joining("\n"));
        actions.add(new RecommendedAction(RecommendationPriority.MEDIUM, buildCommand(log).map(command -> "Run " + command).orElse("Compile the project")
                + " locally and confirm that compilation succeeds", "It shows whether the correction is complete before the pipeline runs again"));
        actions.add(new RecommendedAction(RecommendationPriority.LOW, "Add or retain a compile validation step before the "
                + (IMAGE_BUILD.matcher(log).find() ? "container image build" : "later build steps"), "Compiler errors are then reported earlier"));
        return List.copyOf(actions);
    }

    private static Optional<RecommendedAction> correction(CompilerError error, List<RecommendedAction> proposed, EvidenceMatcher matcher) {
        return proposed.stream().filter(action -> corrects(action, error, matcher)).findFirst()
                .map(action -> new RecommendedAction(RecommendationPriority.HIGH, action.action(), action.reason()));
    }

    private static boolean corrects(RecommendedAction action, CompilerError error, EvidenceMatcher matcher) {
        String text = action.action() + "\n" + action.reason();
        return lines(action.action()).contains(error.line()) && lines(text).equals(Set.of(error.line()))
                && error.isMentionedIn(action.action())
                && SOURCE_FILE.matcher(text).results().allMatch(file -> file.group().equals(error.file()))
                && !matcher.containsInventedClaims(text);
    }

    private static Set<String> lines(String text) {
        Set<String> lines = new HashSet<>();
        Matcher matcher = LINES.matcher(text);
        while (matcher.find()) {
            String numbers = matcher.group(1) != null ? matcher.group(1) : matcher.group(2) != null ? matcher.group(2) : matcher.group(3);
            lines.addAll(List.of(numbers.split("\\D+")));
        }
        return lines;
    }

    /** Without its options, which may contain anything. */
    private static Optional<String> buildCommand(String log) {
        Matcher matcher = BUILD_COMMAND.matcher(log);
        while (matcher.find()) {
            List<String> tasks = List.of(matcher.group("arguments").strip().split("\\s+")).stream()
                    .filter(argument -> !argument.startsWith("-")).toList();
            if (!tasks.isEmpty()) {
                return Optional.of(matcher.group("tool") + " " + String.join(" ", tasks));
            }
        }
        return Optional.empty();
    }
}
