package com.hackathon.agent.investigation.service;

import com.hackathon.agent.ai.llm.LlmProvider;
import com.hackathon.agent.ai.llm.LlmProviderException;
import com.hackathon.agent.ai.llm.LlmResponse;
import com.hackathon.agent.ai.llm.LlmUsage;
import com.hackathon.agent.ai.service.AiQuotaService;
import com.hackathon.agent.evidence.sanitizer.LogSanitizer;
import com.hackathon.agent.investigation.AgentInvestigationProperties;
import com.hackathon.agent.investigation.agent.AgentDecision;
import com.hackathon.agent.investigation.agent.AgentDecisionParser;
import com.hackathon.agent.investigation.agent.AgentDecisionParser.InvalidDecisionException;
import com.hackathon.agent.investigation.agent.AgentPromptBuilder;
import com.hackathon.agent.investigation.agent.AgentPromptBuilder.StepView;
import com.hackathon.agent.investigation.agent.AgentReportValidator;
import com.hackathon.agent.investigation.entity.AgentInvestigationEntity;
import com.hackathon.agent.investigation.entity.AgentInvestigationStepEntity;
import com.hackathon.agent.investigation.model.AgentReport;
import com.hackathon.agent.investigation.model.InvestigationModel.StepAction;
import com.hackathon.agent.investigation.model.InvestigationModel.StepStatus;
import com.hackathon.agent.investigation.service.AgentInvestigationStore.Claim;
import com.hackathon.agent.investigation.service.AgentInvestigationStore.LostClaimException;
import com.hackathon.agent.investigation.tool.AgentTool;
import com.hackathon.agent.investigation.tool.AgentTool.ToolArguments;
import com.hackathon.agent.investigation.tool.AgentToolException;
import com.hackathon.agent.investigation.tool.AgentToolRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.stereotype.Component;

/**
 * Runs one investigation as a bounded loop: ask the model for the next step, validate it, run the chosen read-only
 * tool, add the result to the context and ask again, until the model reports or a limit is reached.
 * <p>
 * The model only proposes; the backend decides. Unknown tools, invalid arguments, repeated calls and exhausted
 * budgets are refused and the refusal is shown to the model, which can adapt. Every question to the model reserves
 * quota first, like the failure analysis. No database transaction is open while the model or a tool runs.
 */
@Component
@ConditionalOnBooleanProperty(name = "agent.investigation.enabled")
public class AgentInvestigationRunner {

    private static final Logger log = LoggerFactory.getLogger(AgentInvestigationRunner.class);

    static final int MAX_SUMMARY = 4000;
    static final int MAX_REASONING = 2000;
    static final int MAX_STEP_ERROR = 2000;

    private final AgentInvestigationStore store;
    private final AgentContextLoader contextLoader;
    private final AgentToolRegistry registry;
    private final AgentPromptBuilder prompts;
    private final AgentDecisionParser parser;
    private final AgentReportValidator reportValidator;
    private final ObjectProvider<LlmProvider> providers;
    private final AiQuotaService quota;
    private final LogSanitizer sanitizer;
    private final AgentInvestigationProperties properties;
    private final Clock clock;

    public AgentInvestigationRunner(AgentInvestigationStore store, AgentContextLoader contextLoader, AgentToolRegistry registry,
                                    AgentPromptBuilder prompts, AgentDecisionParser parser, AgentReportValidator reportValidator,
                                    ObjectProvider<LlmProvider> providers, AiQuotaService quota, LogSanitizer sanitizer,
                                    AgentInvestigationProperties properties, Clock clock) {
        this.store = store;
        this.contextLoader = contextLoader;
        this.registry = registry;
        this.prompts = prompts;
        this.parser = parser;
        this.reportValidator = reportValidator;
        this.providers = providers;
        this.quota = quota;
        this.sanitizer = sanitizer;
        this.properties = properties;
        this.clock = clock;
    }

    /** State of one run, kept in memory. */
    private final class Run {
        final Claim claim;
        final LlmProvider provider;
        final List<StepView> steps = new ArrayList<>();
        /** Results of successful tool calls by step, the only material evidence may cite. */
        final Map<Integer, String> results = new HashMap<>();
        final Set<String> calls = new HashSet<>();
        int toolCalls;

        Run(Claim claim, LlmProvider provider) {
            this.claim = claim;
            this.provider = provider;
        }
    }

    public void run(Claim claim) {
        try {
            investigate(claim);
        } catch (LostClaimException e) {
            log.warn("{}", e.getMessage());
        } catch (RuntimeException e) {
            log.error("Investigation {} failed unexpectedly", claim.id(), e);
            failQuietly(claim, "Unexpected error: " + e.getClass().getSimpleName(), null, null);
        }
    }

    private void investigate(Claim claim) {
        LlmProvider provider = providers.getIfAvailable();
        if (provider == null) {
            store.fail(claim, "AI analysis is disabled (AI_ENABLED=false); the agent needs its LLM provider", null, null, null);
            return;
        }
        AgentInvestigationEntity investigation = store.get(claim);
        Instant deadline = investigation.getStartedAt().plus(Duration.ofSeconds(properties.timeoutSeconds()));
        String context = contextLoader.load(investigation);
        Run run = new Run(claim, provider);
        log.info("Investigation {} started: {} {}/{}", claim.id(), investigation.getTargetType(), investigation.getNamespace(),
                investigation.getTargetName());

        for (int step = 1; step <= properties.maxSteps(); step++) {
            if (Thread.interrupted()) {
                fail(run, "The application stopped during the investigation", null);
                return;
            }
            if (!clock.instant().isBefore(deadline)) {
                fail(run, "Timeout: the investigation took longer than " + properties.timeoutSeconds() + " seconds", null);
                return;
            }
            boolean finalStep = step == properties.maxSteps() || run.toolCalls >= properties.maxToolCalls();
            if (!quota.tryReserve(provider, null)) {
                fail(run, "The daily AI quota is exhausted", null);
                return;
            }
            LlmResponse response;
            try {
                response = provider.generate(prompts.build(context, run.steps, properties.maxSteps() - step,
                        properties.maxToolCalls() - run.toolCalls, finalStep, properties.maxPromptChars()));
            } catch (LlmProviderException e) {
                fail(run, "The LLM request failed: " + e.category() + ": " + e.getMessage(), e.usage());
                return;
            }
            if (handle(run, step, response, finalStep)) {
                return;
            }
        }
        fail(run, "No final report within " + properties.maxSteps() + " steps", null);
    }

    /** @return whether the investigation ended */
    private boolean handle(Run run, int step, LlmResponse response, boolean finalStep) {
        AgentDecision decision;
        try {
            decision = parser.parse(response.text());
        } catch (InvalidDecisionException e) {
            record(run, step, StepAction.INVALID, null, null, null, StepStatus.REJECTED, null,
                    "Invalid answer: " + e.getMessage(), null, response);
            return false;
        }
        if (decision.action() == AgentDecision.Action.FINAL_REPORT) {
            AgentReport report = reportValidator.validate(decision.report(), run.results);
            record(run, step, StepAction.FINAL_REPORT, null, decision, null, StepStatus.COMPLETED,
                    "Final report: " + report.rootCause(), null, null, response);
            store.complete(run.claim, report);
            log.info("Investigation {} completed after {} steps and {} tool calls: category={} confidence={}", run.claim.id(),
                    step, run.toolCalls, report.failureCategory(), report.confidence());
            return true;
        }

        Optional<AgentTool> tool = registry.find(decision.toolName());
        String rejection = finalStep ? "The investigation budget is used up; no more tools may be called"
                : tool.isEmpty() ? "Unknown tool '" + decision.toolName() + "'"
                : registry.validate(decision.resourceName(), decision.namespace()).orElse(null);
        String key = decision.toolName() + "|" + decision.resourceName() + "|" + decision.namespace();
        if (rejection == null && run.calls.contains(key)) {
            rejection = "This tool was already called with the same arguments; use its earlier result";
        }
        if (rejection != null) {
            record(run, step, StepAction.CALL_TOOL, tool.map(AgentTool::name).orElse(null), decision, null, StepStatus.REJECTED,
                    null, rejection, null, response);
            if (finalStep) {
                fail(run, "No final report within the limits (" + properties.maxSteps() + " steps, "
                        + properties.maxToolCalls() + " tool calls)", null);
                return true;
            }
            return false;
        }

        run.calls.add(key);
        run.toolCalls++;
        long started = System.nanoTime();
        try {
            String result = registry.execute(tool.get(), new ToolArguments(decision.resourceName(), decision.namespace()));
            run.results.put(step, result);
            record(run, step, StepAction.CALL_TOOL, tool.get().name(), decision, result, StepStatus.SUCCEEDED,
                    shorten(result, MAX_SUMMARY), null, elapsed(started), response);
        } catch (AgentToolException e) {
            record(run, step, StepAction.CALL_TOOL, tool.get().name(), decision, null, StepStatus.FAILED, null,
                    sanitizer.sanitizeMessage(e.getMessage(), MAX_STEP_ERROR), elapsed(started), response);
        } catch (RuntimeException e) {
            log.warn("Tool {} failed unexpectedly in investigation {}", tool.get().name(), run.claim.id(), e);
            record(run, step, StepAction.CALL_TOOL, tool.get().name(), decision, null, StepStatus.FAILED, null,
                    "The tool failed unexpectedly: " + e.getClass().getSimpleName(), elapsed(started), response);
        }
        return false;
    }

    private void record(Run run, int number, StepAction action, String toolName, AgentDecision decision, String result,
                        StepStatus status, String summary, String error, Long durationMs, LlmResponse response) {
        AgentInvestigationStepEntity step = new AgentInvestigationStepEntity();
        step.setStepNumber(number);
        step.setAction(action);
        step.setToolName(toolName);
        step.setStatus(status);
        step.setDurationMs(durationMs);
        step.setResultSummary(summary == null ? null : sanitizer.sanitizeMessage(summary, MAX_SUMMARY));
        step.setErrorMessage(error == null ? null : shorten(error, MAX_STEP_ERROR));
        if (decision != null) {
            step.setReasoning(sanitizer.sanitizeMessage(decision.reasoning(), MAX_REASONING));
            if (action == StepAction.CALL_TOOL) {
                step.setArguments(arguments(decision));
            }
        }
        store.recordStep(run.claim, step, run.toolCalls, response.usage(), response.provider(), response.model());
        if (action != StepAction.FINAL_REPORT) {
            run.steps.add(new StepView(number, step.getToolName() == null && decision != null ? shorten(decision.toolName(), 64) : step.getToolName(),
                    step.getArguments() == null ? null : step.getArguments().get("resourceName"),
                    step.getArguments() == null ? null : step.getArguments().get("namespace"),
                    status, result, step.getErrorMessage()));
        }
    }

    /** Arguments as requested, which may be anything the model wrote: redacted and bounded before they are stored. */
    private Map<String, String> arguments(AgentDecision decision) {
        Map<String, String> arguments = new LinkedHashMap<>();
        Optional.ofNullable(sanitizer.sanitizeMessage(decision.toolName(), 64)).ifPresent(v -> arguments.put("toolName", v));
        Optional.ofNullable(sanitizer.sanitizeMessage(decision.resourceName(), 253)).ifPresent(v -> arguments.put("resourceName", v));
        Optional.ofNullable(sanitizer.sanitizeMessage(decision.namespace(), 63)).ifPresent(v -> arguments.put("namespace", v));
        return arguments;
    }

    private void fail(Run run, String error, LlmUsage usage) {
        log.warn("Investigation {} failed: {}", run.claim.id(), error);
        store.fail(run.claim, error, usage, run.provider.name(), null);
    }

    private void failQuietly(Claim claim, String error, LlmUsage usage, String provider) {
        // The thread may have been interrupted, but database access needs an uninterrupted one.
        Thread.interrupted();
        try {
            store.fail(claim, error, usage, provider, null);
        } catch (RuntimeException e) {
            log.warn("Investigation {} could not be marked as failed; its lease will expire", claim.id());
        }
    }

    private static long elapsed(long startedNanos) {
        return Duration.ofNanos(System.nanoTime() - startedNanos).toMillis();
    }

    private static String shorten(String text, int max) {
        return text == null || text.length() <= max ? text : text.substring(0, max - 3) + "...";
    }
}
