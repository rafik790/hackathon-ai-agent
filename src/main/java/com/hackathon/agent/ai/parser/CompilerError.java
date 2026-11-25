package com.hackathon.agent.ai.parser;

import com.hackathon.agent.ai.model.EvidenceSource;
import com.hackathon.agent.ai.model.FailureContext;
import com.hackathon.agent.ai.model.SupportingEvidence;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A compiler error in the log of a failed container. It proves by itself why the build failed, so it can be
 * used without the model. Every text made from it repeats the log.
 *
 * @param container container whose log contains the error
 * @param logLine   the line of the log
 * @param file      file name without its directory
 * @param line      line number in the file
 * @param message   message of the compiler
 */
record CompilerError(String container, String logLine, String file, String line, String message) {

    private static final int MAX_LOG_LINE = 500;
    private static final int MAX_MESSAGE = 120;

    private static final String SOURCE_FILE = "(?<file>[^\\s:(\\[]{0,300}\\.(?:java|kts?|scala|groovy|c|cc|cpp|cxx|h|hpp|cs|go|rs|swift|tsx?))";
    /** javac and gcc/clang, kotlinc, Maven, tsc. */
    private static final List<Pattern> FORMATS = List.of(
            Pattern.compile("(?<!\\S)" + SOURCE_FILE + ":(?<line>\\d{1,7})(?::\\d{1,5})?: (?:fatal )?error: (?<message>.+)"),
            Pattern.compile("(?<!\\S)e: (?:file://)?" + SOURCE_FILE + ":(?<line>\\d{1,7}):\\d{1,5}:? (?<message>.+)"),
            Pattern.compile("\\[ERROR] " + SOURCE_FILE + ":\\[(?<line>\\d{1,7}),\\d{1,5}] (?:error: )?(?<message>.+)"),
            Pattern.compile("(?<!\\S)" + SOURCE_FILE + "\\((?<line>\\d{1,7}),\\d{1,5}\\): error TS\\d{1,6}: (?<message>.+)"));
    /** Compiling failed, as opposed to a compiler error that is only mentioned or was tolerated. */
    private static final Pattern COMPILING_FAILED = Pattern.compile("(?im):compile\\w{0,60} FAILED|compil\\w{0,10} failed|COMPILATION ERROR"
            + "|(?:failed to|could not) compile|found [1-9]\\d{0,5} errors?\\b|^\\s{0,20}[1-9]\\d{0,5} errors?\\s{0,20}$");
    /** After such an error the compiler misunderstands the rest of the file. */
    private static final Pattern SYNTAX_ERROR = Pattern.compile("(?i)^(?:unclosed|unterminated)\\b|\\bexpected\\b|^illegal (?:start|character)|^not a statement|^reached end of file");
    private static final Set<String> NOT_A_CAUSE = Set.of("Skipped", "Continued");

    /** The errors of the first log that contains some, in the order in which they were reported. */
    static List<CompilerError> findAll(FailureContext context) {
        for (FailureContext.ContainerLog log : context.logs()) {
            if (log.content() == null || !failed(context, log.containerName()) || !COMPILING_FAILED.matcher(log.content()).find()) {
                continue;
            }
            List<CompilerError> errors = new ArrayList<>();
            EvidenceMatcher.withoutAnsi(log.content()).lines()
                    .forEach(line -> parse(log.containerName(), line.strip()).ifPresent(errors::add));
            if (!errors.isEmpty()) {
                return errors;
            }
        }
        return List.of();
    }

    private static boolean failed(FailureContext context, String containerName) {
        return context.containers().stream().filter(c -> c.name().equals(containerName))
                .anyMatch(c -> c.exitCode() != null && c.exitCode() != 0
                        && (c.tektonTerminationReason() == null || !NOT_A_CAUSE.contains(c.tektonTerminationReason())));
    }

    private static Optional<CompilerError> parse(String container, String text) {
        if (text.length() > MAX_LOG_LINE) {
            return Optional.empty();
        }
        for (Pattern format : FORMATS) {
            Matcher matcher = format.matcher(text);
            if (matcher.find()) {
                String file = matcher.group("file");
                return Optional.of(new CompilerError(container, text, file.substring(file.lastIndexOf('/') + 1),
                        matcher.group("line"), matcher.group("message").strip()));
            }
        }
        return Optional.empty();
    }

    /** Whether a text is about this error. */
    boolean isMentionedIn(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        return lower.contains(file.toLowerCase(Locale.ROOT)) || lower.contains(message.toLowerCase(Locale.ROOT));
    }

    /** Whether this error, reported later, was probably only caused by the other one. */
    boolean isConsequenceOf(CompilerError earlier) {
        return file.equals(earlier.file) && SYNTAX_ERROR.matcher(earlier.message).find();
    }

    String summary() {
        return "Compilation failed in " + description() + ".";
    }

    String rootCause() {
        return "The compiler reported an error in container " + container + ": " + description() + ".";
    }

    SupportingEvidence evidence() {
        return new SupportingEvidence(EvidenceSource.CONTAINER_LOG, container, logLine);
    }

    String correction() {
        String location = file + ", line " + line;
        String text = shortMessage();
        // "unclosed string literal" names the thing to fix, other messages are sentences.
        return text.matches("(?i)(?:unclosed|unterminated) [\\w ]+") ? "Fix the " + text.toLowerCase(Locale.ROOT) + " in " + location
                : "Fix the compiler error in " + location + ": " + text;
    }

    private String shortMessage() {
        return message.length() > MAX_MESSAGE ? message.substring(0, MAX_MESSAGE) + "..." : message.replaceAll("\\.$", "");
    }

    private String description() {
        String text = shortMessage();
        // The only explanation that is added: it is what this message of the compiler means.
        String meaning = message.startsWith("unclosed string literal") ? " (missing closing quote)" : "";
        return file + ":" + line + ": " + text + meaning;
    }
}
