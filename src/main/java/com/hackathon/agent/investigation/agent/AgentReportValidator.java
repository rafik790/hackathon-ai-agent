package com.hackathon.agent.investigation.agent;

import com.hackathon.agent.ai.model.FailureCategory;
import com.hackathon.agent.evidence.sanitizer.LogSanitizer;
import com.hackathon.agent.investigation.agent.AgentDecision.ProposedEvidence;
import com.hackathon.agent.investigation.agent.AgentDecision.ProposedReport;
import com.hackathon.agent.investigation.agent.AgentDecision.ProposedStep;
import com.hackathon.agent.investigation.agent.AgentDecision.ProposedTeamCheck;
import com.hackathon.agent.investigation.model.AgentReport;
import com.hackathon.agent.investigation.model.AgentReport.Ownership;
import com.hackathon.agent.investigation.model.AgentReport.OwnershipConfidence;
import com.hackathon.agent.investigation.model.AgentReport.Step;
import com.hackathon.agent.investigation.model.AgentReport.Team;
import com.hackathon.agent.investigation.model.AgentReport.TeamCheck;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Accepts only what the investigation can back: evidence must be found literally in the result of the step it cites,
 * and without such evidence the confidence is limited. Ownership is only confident when the cause is proven, an
 * uncertain report always says what is missing, and commands are dropped when they are unsafe or name a namespace
 * or resource the investigation never saw. All text is redacted and bounded.
 */
public class AgentReportValidator {

    static final BigDecimal MAX_CONFIDENCE_UNVERIFIED = new BigDecimal("0.300");
    /** Below this report confidence the owner is at most MEDIUM. */
    static final BigDecimal MIN_CONFIDENCE_HIGH_OWNERSHIP = new BigDecimal("0.5");
    static final int MAX_SUMMARY = 2000;
    static final int MAX_ROOT_CAUSE = 4000;
    static final int MAX_TEXT = 1000;
    static final int MAX_TITLE = 160;
    static final int MAX_COMMAND = 3000;
    private static final int MIN_OBSERVATION = 4;

    static final String DEFAULT_MISSING = "No tool result shows the cause of the failure, so neither the fix nor the owner can be confirmed.";
    static final String DEFAULT_MISSING_OWNER = "The evidence does not show which team owns the failing resource or configuration.";
    static final String DEFAULT_NEXT_ACTION = "Read the logs of the failed TaskRun and the events of its Pod, then start a new investigation.";

    /** Commands a person must never be told to run: they delete, bypass security or grant far too much. */
    private static final List<Pattern> UNSAFE = Stream.of(
            "\\bkubectl\\s+(delete|drain|cordon)\\b",
            "\\brm\\s+-[a-z]*r[a-z]*f",
            "--insecure-skip-tls-verify",
            "--force\\b",
            "\\bcluster-admin\\b",
            "chmod\\s+(-r\\s+)?777",
            "privileged:\\s*true",
            "(verbs|resources|apigroups):\\s*\\[?\\s*[\"']?\\*")
            .map(regex -> Pattern.compile(regex, Pattern.CASE_INSENSITIVE)).toList();

    private static final String NAME = "([a-z0-9][a-z0-9.-]*[a-z0-9]|[a-z0-9])";
    /** Existing namespaces, service accounts and resources a command refers to; each must occur in the evidence. */
    private static final List<Pattern> REFERENCES = Stream.of(
            "(?:\\s-n|--namespace)[=\\s]+" + NAME,
            "\\bnamespace:\\s*[\"']?" + NAME,
            "system:serviceaccount:" + NAME + ":" + NAME,
            "--serviceaccount[=\\s]+" + NAME + ":" + NAME,
            "\\bserviceAccountName:\\s*[\"']?" + NAME,
            "kind:\\s*ServiceAccount\\s*\\n\\s*name:\\s*[\"']?" + NAME,
            "\\b(?:deployments?|deploy|pods?|pipelineruns?|taskruns?|serviceaccounts?|sa)(?:\\.[a-z.]+)?/" + NAME,
            "\\bkubectl\\s+(?:get|describe|logs)\\s+(?:deployments?|deploy|pods?|pipelineruns?|taskruns?|serviceaccounts?|sa)\\s+" + NAME,
            "\\btkn\\s+(?:pipelinerun|taskrun|pr|tr)\\s+(?:describe|logs)\\s+" + NAME)
            .map(regex -> Pattern.compile(regex, Pattern.CASE_INSENSITIVE)).toList();

    private final LogSanitizer sanitizer;

    public AgentReportValidator(LogSanitizer sanitizer) {
        this.sanitizer = sanitizer;
    }

    public AgentReport validate(ProposedReport report, Map<Integer, String> toolResults) {
        return validate(report, toolResults, "");
    }

    /**
     * @param toolResults  results of the successful tool steps, by step number
     * @param knownContext what was known before the investigation; names in it count as seen
     */
    public AgentReport validate(ProposedReport report, Map<Integer, String> toolResults, String knownContext) {
        List<AgentReport.Evidence> verified = new ArrayList<>();
        int unverified = 0;
        for (ProposedEvidence item : report.evidence()) {
            String result = toolResults.get(item.step());
            String observation = sanitizer.sanitizeMessage(item.observation(), MAX_TEXT);
            if (result != null && observation != null && isQuoted(observation, result)) {
                verified.add(new AgentReport.Evidence(item.step(), observation));
            } else {
                unverified++;
            }
        }
        BigDecimal confidence = report.confidence().max(BigDecimal.ZERO).min(BigDecimal.ONE).setScale(3, RoundingMode.HALF_UP);
        if (verified.isEmpty()) {
            confidence = confidence.min(MAX_CONFIDENCE_UNVERIFIED);
        }
        String category = category(report.failureCategory());
        String summary = text(report.summary() == null ? report.rootCause() : report.summary(), MAX_SUMMARY);

        Team team = team(report.ownerTeam());
        boolean causeUnproven = verified.isEmpty() || FailureCategory.UNKNOWN.name().equals(category);
        Ownership ownership = new Ownership(team, ownershipConfidence(report.ownershipConfidence(), team, causeUnproven, confidence),
                optionalText(report.ownershipReason(), MAX_TEXT).orElse(team == Team.UNKNOWN ? DEFAULT_MISSING_OWNER : ""));

        String corpus = normalizeNames(unescapeJson(String.join("\n", toolResults.values()) + "\n" + (knownContext == null ? "" : knownContext)));
        Commands commands = new Commands(corpus);
        List<Step> resolution = commands.steps(report.resolutionSteps());
        List<Step> verification = commands.steps(report.verificationSteps());

        List<String> missing = new ArrayList<>(texts(report.missingInformation()));
        String nextAction = optionalText(report.nextDiagnosticAction(), MAX_TEXT).orElse(null);
        if (causeUnproven || team == Team.UNKNOWN) {
            if (missing.isEmpty()) {
                missing.add(causeUnproven ? DEFAULT_MISSING : DEFAULT_MISSING_OWNER);
            }
            if (nextAction == null) {
                nextAction = DEFAULT_NEXT_ACTION;
            }
        }
        List<String> recommended = texts(report.recommendedActions());
        if (recommended.isEmpty()) {
            recommended = resolution.stream().map(Step::action).toList();
        }

        return new AgentReport(
                summary,
                text(report.rootCause(), MAX_ROOT_CAUSE),
                category,
                confidence,
                List.copyOf(verified),
                recommended,
                unverified,
                optionalText(report.title(), MAX_TITLE).orElseGet(() -> title(summary)),
                ownership,
                resolution,
                report.otherTeamChecks().stream().map(this::check).flatMap(Optional::stream).toList(),
                verification,
                optionalText(report.expectedOutcome(), MAX_TEXT).orElse(""),
                List.copyOf(missing),
                nextAction,
                commands.removed);
    }

    /** Whether the observation, apart from whitespace and case, occurs in the tool result. */
    static boolean isQuoted(String observation, String result) {
        String quote = normalize(observation);
        return quote.length() >= MIN_OBSERVATION && normalize(unescapeJson(result)).contains(quote);
    }

    /** Why a command is not shown, or empty when it may be shown. */
    static Optional<String> commandProblem(String command, String normalizedCorpus) {
        for (Pattern unsafe : UNSAFE) {
            if (unsafe.matcher(command).find()) {
                return Optional.of("unsafe: " + unsafe.pattern());
            }
        }
        for (Pattern reference : REFERENCES) {
            Matcher matcher = reference.matcher(command);
            while (matcher.find()) {
                for (int group = 1; group <= matcher.groupCount(); group++) {
                    String name = matcher.group(group).toLowerCase(Locale.ROOT);
                    if (!Pattern.compile("(?<![a-z0-9-])" + Pattern.quote(name) + "(?![a-z0-9-])").matcher(normalizedCorpus).find()) {
                        return Optional.of("not in the evidence: " + name);
                    }
                }
            }
        }
        return Optional.empty();
    }

    /**
     * The owner is only as certain as the cause: LOW when the cause is unproven or no team was named, at most MEDIUM
     * when the report itself is not confident.
     */
    private static OwnershipConfidence ownershipConfidence(String value, Team team, boolean causeUnproven, BigDecimal confidence) {
        OwnershipConfidence proposed = OwnershipConfidence.LOW;
        if (value != null) {
            try {
                proposed = OwnershipConfidence.valueOf(value.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                // unknown values are LOW
            }
        }
        if (causeUnproven || team == Team.UNKNOWN) {
            return OwnershipConfidence.LOW;
        }
        if (proposed == OwnershipConfidence.HIGH && confidence.compareTo(MIN_CONFIDENCE_HIGH_OWNERSHIP) < 0) {
            return OwnershipConfidence.MEDIUM;
        }
        return proposed;
    }

    private static Team team(String value) {
        if (value != null) {
            String name = value.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z]+", "_").replaceAll("^_|_$", "");
            if (name.equals("DEVOPS") || name.equals("PLATFORM") || name.equals("DEVOPS_PLATFORM")) {
                return Team.DEVOPS_PLATFORM;
            }
            if (name.equals("CI_CD_ENGINEER") || name.equals("CICD")) {
                return Team.CICD_ENGINEER;
            }
            try {
                return Team.valueOf(name);
            } catch (IllegalArgumentException e) {
                // unknown teams are UNKNOWN
            }
        }
        return Team.UNKNOWN;
    }

    private Optional<TeamCheck> check(ProposedTeamCheck check) {
        return optionalText(check.request(), MAX_TEXT).map(request -> new TeamCheck(team(check.team()), request));
    }

    private static String category(String value) {
        if (value != null) {
            try {
                return FailureCategory.valueOf(value.trim().toUpperCase(Locale.ROOT)).name();
            } catch (IllegalArgumentException e) {
                // unknown categories become UNKNOWN
            }
        }
        return FailureCategory.UNKNOWN.name();
    }

    /** First sentence of the summary, for reports without a title. */
    private static String title(String summary) {
        int end = summary.indexOf(". ");
        String sentence = end > 0 ? summary.substring(0, end) : summary;
        return sentence.length() <= MAX_TITLE ? sentence : sentence.substring(0, MAX_TITLE - 3) + "...";
    }

    private String text(String value, int max) {
        return optionalText(value, max).orElse("");
    }

    private Optional<String> optionalText(String value, int max) {
        return Optional.ofNullable(sanitizer.sanitizeMessage(value, max)).map(String::strip).filter(text -> !text.isEmpty());
    }

    private List<String> texts(List<String> values) {
        return values.stream().map(value -> optionalText(value, MAX_TEXT)).flatMap(Optional::stream).toList();
    }

    /** Tool results are JSON, so quotes and line breaks in them are escaped. */
    private static String unescapeJson(String text) {
        return text.replace("\\\"", "\"").replace("\\n", "\n").replace("\\t", "\t").replace("\\\\", "\\");
    }

    private static String normalize(String text) {
        return text.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").strip();
    }

    private static String normalizeNames(String text) {
        return text.toLowerCase(Locale.ROOT);
    }

    /** Checks the commands of a list of steps and counts the removed ones. */
    private final class Commands {

        private final String corpus;
        private int removed;

        Commands(String corpus) {
            this.corpus = corpus;
        }

        List<Step> steps(List<ProposedStep> steps) {
            List<Step> accepted = new ArrayList<>();
            for (ProposedStep step : steps) {
                Optional<String> action = optionalText(step.action(), MAX_TEXT);
                if (action.isEmpty()) {
                    continue;
                }
                String command = step.command() == null ? null : sanitizer.sanitizeMessage(step.command(), MAX_COMMAND);
                if (command != null && commandProblem(command, corpus).isPresent()) {
                    removed++;
                    command = null;
                }
                accepted.add(new Step(action.get(), command));
            }
            return List.copyOf(accepted);
        }
    }
}
