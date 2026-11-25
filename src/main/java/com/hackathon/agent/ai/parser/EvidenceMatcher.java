package com.hackathon.agent.ai.parser;

import com.hackathon.agent.ai.model.FailureContext;
import com.hackathon.agent.ai.model.SupportingEvidence;
import com.hackathon.agent.evidence.model.ExitCodeSource;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Compares statements of the model with the context it was given. It checks what can be checked literally: quotes,
 * names, exit codes, timestamps, reasons and other identifiers. It cannot judge whether a conclusion is logical.
 */
final class EvidenceMatcher {

    /** How well a piece of evidence is backed by the context. */
    enum Match {
        /** Not found in the source it refers to, or contradicting it. */
        NONE,
        /** True, but it only says that something failed, e.g. an exit code or the reason "Failed". */
        GENERIC,
        /** Found in a log, message or meaningful reason. */
        SPECIFIC
    }

    private static final int MIN_QUOTE = 4;
    private static final double MIN_WORD_OVERLAP = 0.6;

    private static final Pattern ANSI = Pattern.compile("\u001B\\[[0-9;]*[A-Za-z]");
    /**
     * A quoted passage neither starts nor ends with a space. Otherwise its quotation mark belongs to quoted source code,
     * e.g. {@code println("abc);} or {@code ';' expected}, and the text up to the next one is not a quote.
     */
    private static final Pattern QUOTE = Pattern.compile(
            "\"(?!\\s)([^\"\\n]{2,500})(?<!\\s)\"|`([^`\\n]{2,500})`"
                    + "|(?<![\\p{L}\\p{N}])'(?!\\s)([^'\\n]{2,500})(?<!\\s)'(?![\\p{L}\\p{N}])");
    private static final Pattern EXIT_CODE = Pattern.compile(
            "(?i)\\bexit(?:ed|s)?[ _-]?(?:with )?(?:a )?(?:non-?zero )?(?:exit )?(?:status|code)?(?: of)?\\s{0,3}[:=]?\\s{0,3}(\\d{1,3})\\b");
    /** "step-level" and "step-by-step" are ordinary words. */
    private static final Pattern CONTAINER = Pattern.compile("\\b(?:step|sidecar)-(?!(?:level|by-step)(?![a-z0-9-]))[a-z0-9](?:[a-z0-9-]*[a-z0-9])?");
    private static final Pattern POD = Pattern.compile("\\b[a-z0-9][a-z0-9-]*-pod(?:-retry\\d+)?\\b");
    private static final Pattern TIME = Pattern.compile("\\b\\d{4}-\\d{2}-\\d{2}(?:[T ]\\d{2}:\\d{2}(?::\\d{2})?)?|\\b\\d{2}:\\d{2}:\\d{2}\\b");
    /** Identifiers a model could only know from the context. */
    private static final List<Pattern> IDENTIFIERS = List.of(
            CONTAINER, POD,
            Pattern.compile("\\b(?:OOMKilled|ImagePullBackOff|ErrImagePull|InvalidImageName|CrashLoopBackOff|CreateContainerConfigError"
                    + "|CreateContainerError|RunContainerError|ContainerCannotRun|StartError|Evicted|NodeLost|NodeNotReady|FailedScheduling"
                    + "|Unschedulable|DeadlineExceeded|DiskPressure|MemoryPressure|FailedMount|TaskRunTimeout|TaskRunCancelled"
                    + "|PipelineRunTimeout|TaskRunImagePullFailed)\\b"),
            Pattern.compile("\\b[A-Z][A-Za-z0-9]*[a-z0-9](?:Exception|Error)\\b"),
            Pattern.compile("https?://[^\\s\"'`)>,]+"),
            Pattern.compile("\\b\\d{1,3}(?:\\.\\d{1,3}){3}\\b"),
            Pattern.compile("\\b[a-z0-9][a-z0-9.-]*(?:/[a-z0-9._-]+)+:[\\w][\\w.-]*"));
    private static final Pattern HTTP_STATUS = Pattern.compile("(?i)\\b(?:http|status code|http status)\\s{0,2}[:=]?\\s{0,2}([1-5]\\d{2})\\b");
    private static final Pattern RESTARTS = Pattern.compile("(?i)\\brestart(?:ed|s|[ _-]?count)?\\s{0,2}[:=]?\\s{0,2}(\\d{1,3})\\b");
    private static final Pattern SIGNAL = Pattern.compile("(?i)\\bsignal\\s{0,2}[:=]?\\s{0,2}(\\d{1,2})\\b");
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}][\\p{L}\\p{N}_.-]{3,}");
    /** Words that appear in nearly every statement about a failure and therefore prove nothing. */
    private static final Set<String> COMMON_WORDS = Set.of("failed", "failure", "error", "exited", "with", "code", "exit",
            "step", "container", "taskrun", "pipelinerun", "task", "pipeline", "from", "that", "this", "because", "were",
            "been", "have", "shows", "status", "reason", "message", "during", "after", "before", "when", "while", "which",
            "would", "could", "caused", "build", "reported", "logs", "into", "command", "execution", "exitcode", "tasks",
            "completed", "cancelled", "skipped", "incomplete", "terminated", "steps", "containers", "unknown", "nonzero",
            "non-zero");
    /** Reasons that only say that something failed. */
    private static final Set<String> GENERIC_REASONS = Set.of("failed", "error", "completed", "stepfailed", "unknown",
            "succeeded", "running", "skipped", "continued", "terminated", "waiting");

    private final FailureContext context;
    private final String corpus;
    private final Set<String> corpusExitCodes;

    EvidenceMatcher(FailureContext context) {
        this.context = context;
        this.corpus = normalize(String.join("\n", allText()));
        this.corpusExitCodes = exitCodes(corpus);
        context.containers().forEach(c -> {
            // Filled in by Tekton: no container exited with this code.
            if (c.exitCode() != null && c.exitCodeSource() != ExitCodeSource.SYNTHETIC) {
                corpusExitCodes.add(c.exitCode().toString());
            }
        });
    }

    /** Whether the text contains details, including quotes, that do not exist in the context. */
    boolean containsInventedDetails(String text) {
        return containsInventedIdentifiers(text) || quotes(text).stream().anyMatch(quote -> !contains(corpus, quote));
    }

    /**
     * Like {@link #containsInventedDetails}, but allows single words in quotation marks for emphasis, and quotes that
     * only use words of the context, e.g. a log line with different punctuation.
     */
    boolean containsInventedClaims(String text) {
        return containsInventedIdentifiers(text) || quotes(text).stream()
                .anyMatch(quote -> quote.contains(" ") && !contains(corpus, quote) && !consistsOfKnownWords(quote));
    }

    private boolean consistsOfKnownWords(String quote) {
        List<String> words = findAll(WORD, quote).stream().map(EvidenceMatcher::stripPunctuation).toList();
        return !words.isEmpty() && words.stream().allMatch(word -> containsWord(corpus, word));
    }

    /** Like {@link #containsInventedDetails}, but allows quotation marks for other purposes. */
    boolean containsInventedIdentifiers(String text) {
        for (Pattern pattern : IDENTIFIERS) {
            for (String identifier : findAll(pattern, text)) {
                if (!contains(corpus, stripPunctuation(identifier))) {
                    return true;
                }
            }
        }
        for (String time : findAll(TIME, text)) {
            if (!corpus.contains(normalize(time)) && !corpus.contains(normalize(time.replace(' ', 'T')))
                    && !corpus.contains(normalize(time.replace('T', ' ')))) {
                return true;
            }
        }
        Matcher http = HTTP_STATUS.matcher(text);
        while (http.find()) {
            if (!containsWord(corpus, http.group(1))) {
                return true;
            }
        }
        return contradictsExitCodes(text) || contradictsNumbers(RESTARTS, text) || contradictsNumbers(SIGNAL, text);
    }

    /** Whether a statement is recognizably about something in the context. */
    boolean isGrounded(String text) {
        if (containsInventedDetails(text)) {
            return false;
        }
        if (!quotes(text).isEmpty() || !exitCodes(text).isEmpty()) {
            return true;
        }
        String normalized = normalize(text);
        if (contains(corpus, normalized) || knownNames().stream().anyMatch(name -> containsWord(normalized, normalize(name)))) {
            return true;
        }
        return wordOverlap(normalized) >= MIN_WORD_OVERLAP;
    }

    Match match(SupportingEvidence evidence) {
        String observation = evidence.observation();
        if (evidence.containerName() != null && !context.containerNames().contains(evidence.containerName())) {
            return Match.NONE;
        }
        if (containsInventedDetails(observation)) {
            return Match.NONE;
        }
        return switch (evidence.source()) {
            case CONTAINER_LOG -> !quoted(observation, logText(evidence.containerName())) ? Match.NONE
                    : isInformative(observation) ? Match.SPECIFIC : Match.GENERIC;
            case CONTAINER_STATUS -> matchContainer(evidence.containerName(), observation);
            case POD_STATUS -> context.pod() == null ? Match.NONE : matchFields(observation,
                    values(context.pod().message(), context.pod().reason()),
                    values(context.pod().phase(), context.pod().name(), context.pod().uid()));
            case TASK_RUN -> matchFields(observation, taskRunDetails(), taskRunNames());
            case PIPELINE_RUN -> context.pipelineRun() == null ? Match.NONE : matchFields(observation,
                    values(context.pipelineRun().failureMessage(), context.pipelineRun().failureReason()),
                    values(context.pipelineRun().status(), context.pipelineRun().name(), context.pipelineRun().pipelineName(),
                            context.pipelineRun().startTime(), context.pipelineRun().completionTime()));
        };
    }

    private Match matchContainer(String containerName, String observation) {
        Match best = Match.NONE;
        for (FailureContext.Container c : context.containers()) {
            if (containerName != null && !containerName.equals(c.name())) {
                continue;
            }
            if (exitCodes(observation).stream().anyMatch(code -> c.exitCode() != null && code.equals(c.exitCode().toString())
                    && c.exitCodeSource() != ExitCodeSource.SYNTHETIC)) {
                best = max(best, Match.GENERIC);
            }
            best = max(best, matchFields(observation, values(c.message(), c.reason(), c.tektonTerminationReason()), values(c.state())));
        }
        return best;
    }

    /** @param details fields that explain something; @param names fields that only identify or describe a state */
    private Match matchFields(String observation, List<String> details, List<String> names) {
        String normalized = normalize(observation);
        for (String detail : details) {
            if (isInformative(detail) && containsWord(normalized, normalize(detail))) {
                return Match.SPECIFIC;
            }
            if (quoted(observation, normalize(detail)) && isInformative(observation)) {
                return Match.SPECIFIC;
            }
        }
        for (String value : names) {
            if (containsWord(normalized, normalize(value))) {
                return Match.GENERIC;
            }
        }
        return details.stream().anyMatch(detail -> containsWord(normalized, normalize(detail))
                || quoted(observation, normalize(detail))) ? Match.GENERIC : Match.NONE;
    }

    /** Whether the observation, or every quote in it, occurs literally in the source. */
    private static boolean quoted(String observation, String source) {
        if (source.isEmpty()) {
            return false;
        }
        List<String> quotes = quotes(observation);
        if (!quotes.isEmpty()) {
            return quotes.stream().allMatch(quote -> contains(source, quote));
        }
        String text = stripPunctuation(normalize(observation));
        return text.length() >= MIN_QUOTE && contains(source, text);
    }

    private boolean contradictsExitCodes(String text) {
        Set<String> codes = exitCodes(text);
        if (codes.isEmpty()) {
            return false;
        }
        if (!corpusExitCodes.containsAll(codes)) {
            return true;
        }
        // A statement about one container must use the code of that container.
        List<FailureContext.Container> mentioned = context.containers().stream()
                .filter(c -> containsWord(normalize(text), normalize(c.name()))).toList();
        if (mentioned.size() != 1) {
            return false;
        }
        FailureContext.Container c = mentioned.getFirst();
        Set<String> known = exitCodes(logText(c.name()) + "\n" + normalize(c.message()) + "\n" + normalize(context.taskRun().failureMessage()));
        if (c.exitCode() != null && c.exitCodeSource() != ExitCodeSource.SYNTHETIC) {
            known.add(c.exitCode().toString());
        }
        return !known.containsAll(codes);
    }

    private boolean contradictsNumbers(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            String number = matcher.group(1);
            boolean known = context.containers().stream()
                    .map(c -> pattern == RESTARTS ? c.restartCount() : c.signal())
                    .anyMatch(value -> value != null && number.equals(value.toString()));
            if (!known && !contains(corpus, matcher.group())) {
                return true;
            }
        }
        return false;
    }

    /** Whether a text says more than that something failed. */
    private boolean isInformative(String text) {
        String normalized = normalize(text);
        if (GENERIC_REASONS.contains(normalized)) {
            return false;
        }
        for (String name : knownNames()) {
            normalized = normalized.replace(normalize(name), " ");
        }
        return findAll(WORD, normalized).stream().map(EvidenceMatcher::stripPunctuation)
                .anyMatch(word -> word.chars().anyMatch(Character::isLetter) && !COMMON_WORDS.contains(word));
    }

    private double wordOverlap(String normalized) {
        Set<String> words = new HashSet<>(findAll(WORD, normalized));
        words.removeAll(COMMON_WORDS);
        if (words.isEmpty()) {
            return 0;
        }
        return (double) words.stream().filter(word -> containsWord(corpus, word)).count() / words.size();
    }

    private String logText(String containerName) {
        List<String> logs = context.logs().stream()
                .filter(log -> log.content() != null && (containerName == null || containerName.equals(log.containerName())))
                .map(FailureContext.ContainerLog::content).toList();
        return normalize(String.join("\n", logs));
    }

    private List<String> taskRunDetails() {
        List<String> details = new ArrayList<>(values(context.taskRun().failureMessage(), context.taskRun().failureReason()));
        context.otherTaskRuns().forEach(other -> details.addAll(values(other.failureMessage(), other.failureReason())));
        return details;
    }

    private List<String> taskRunNames() {
        FailureContext.TaskRun t = context.taskRun();
        List<String> names = new ArrayList<>(values(t.status(), t.name(), t.taskName(), t.startTime(), t.completionTime()));
        context.otherTaskRuns().forEach(other -> names.addAll(values(other.name(), other.taskName())));
        return names;
    }

    private List<String> knownNames() {
        List<String> names = new ArrayList<>(context.containerNames());
        names.addAll(values(context.taskRun().name(), context.taskRun().taskName()));
        context.otherTaskRuns().forEach(other -> names.addAll(values(other.name(), other.taskName())));
        if (context.pod() != null) {
            names.addAll(values(context.pod().name()));
        }
        if (context.pipelineRun() != null) {
            names.addAll(values(context.pipelineRun().name(), context.pipelineRun().pipelineName()));
        }
        return names;
    }

    private List<String> allText() {
        List<String> text = new ArrayList<>();
        FailureContext.PipelineRun run = context.pipelineRun();
        if (run != null) {
            text.addAll(values(run.name(), run.uid(), run.pipelineName(), run.namespace(), run.status(), run.startTime(),
                    run.completionTime(), run.failureReason(), run.failureMessage()));
        }
        FailureContext.TaskRun t = context.taskRun();
        text.addAll(values(t.name(), t.uid(), t.taskName(), t.namespace(), t.status(), t.startTime(), t.completionTime(),
                t.failureReason(), t.failureMessage(), context.evidenceStatus()));
        FailureContext.Pod pod = context.pod();
        if (pod != null) {
            text.addAll(values(pod.name(), pod.uid(), pod.phase(), pod.reason(), pod.message()));
        }
        for (FailureContext.Container c : context.containers()) {
            text.addAll(values(c.name(), c.type(), c.stepName(), c.state(), c.reason(), c.tektonTerminationReason(), c.message(),
                    c.exitCodeSource()));
        }
        for (FailureContext.ContainerLog log : context.logs()) {
            text.addAll(values(log.availability(), log.content()));
        }
        for (FailureContext.OtherTaskRun other : context.otherTaskRuns()) {
            text.addAll(values(other.name(), other.taskName(), other.status(), other.startTime(), other.completionTime(),
                    other.failureReason(), other.failureMessage()));
        }
        text.addAll(context.limitations());
        return text;
    }

    private static List<String> values(Object... values) {
        List<String> texts = new ArrayList<>();
        for (Object value : values) {
            if (value != null && !value.toString().isBlank()) {
                texts.add(value.toString());
            }
        }
        return texts;
    }

    /** Quoted passages; a passage shortened with an ellipsis yields its parts. */
    private static List<String> quotes(String text) {
        List<String> quotes = new ArrayList<>();
        Matcher matcher = QUOTE.matcher(text.replace('“', '"').replace('”', '"').replace('‘', '\'').replace('’', '\''));
        while (matcher.find()) {
            String quote = matcher.group(1) != null ? matcher.group(1) : matcher.group(2) != null ? matcher.group(2) : matcher.group(3);
            for (String part : quote.split("\\.{3}|…")) {
                String normalized = stripPunctuation(normalize(part));
                if (normalized.length() >= MIN_QUOTE) {
                    quotes.add(normalized);
                }
            }
        }
        return quotes;
    }

    private static Set<String> exitCodes(String text) {
        Set<String> codes = new HashSet<>();
        Matcher matcher = EXIT_CODE.matcher(text);
        while (matcher.find()) {
            codes.add(String.valueOf(Integer.parseInt(matcher.group(1))));
        }
        return codes;
    }

    private static List<String> findAll(Pattern pattern, String text) {
        return pattern.matcher(text).results().map(result -> result.group()).toList();
    }

    private static boolean contains(String normalizedText, String part) {
        String normalized = normalize(part);
        return !normalized.isEmpty() && normalizedText.contains(normalized);
    }

    private static boolean containsWord(String normalizedText, String word) {
        if (word.isEmpty()) {
            return false;
        }
        int from = 0;
        int index;
        while ((index = normalizedText.indexOf(word, from)) >= 0) {
            int end = index + word.length();
            boolean startsWord = index == 0 || !isNameCharacter(normalizedText.charAt(index - 1)) || !isNameCharacter(word.charAt(0));
            boolean endsWord = end == normalizedText.length() || !isNameCharacter(normalizedText.charAt(end))
                    || !isNameCharacter(word.charAt(word.length() - 1));
            if (startsWord && endsWord) {
                return true;
            }
            from = index + 1;
        }
        return false;
    }

    private static boolean isNameCharacter(char c) {
        return Character.isLetterOrDigit(c) || c == '-' || c == '_';
    }

    private static String stripPunctuation(String text) {
        return text.strip().replaceAll("^[\\s.,;:!?]+|[\\s.,;:!?]+$", "");
    }

    private static String normalize(String text) {
        if (text == null) {
            return "";
        }
        return ANSI.matcher(text).replaceAll("").replace('“', '"').replace('”', '"').replace('‘', '\'').replace('’', '\'')
                .toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").strip();
    }

    private static Match max(Match a, Match b) {
        return a.compareTo(b) >= 0 ? a : b;
    }
}
