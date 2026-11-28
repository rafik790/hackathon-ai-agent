package com.hackathon.agent.investigation.model;

import java.math.BigDecimal;
import java.util.List;

/**
 * Final report of an investigation, after validation by the backend. Stored as JSON: reports written before the
 * ownership and resolution fields existed are read with those fields null.
 *
 * @param failureCategory    a {@code FailureCategory} name
 * @param confidence         0 to 1; at most 0.3 without verified evidence
 * @param evidence           observations found literally in the result of the cited step
 * @param recommendedActions short form of the resolution, kept for existing clients
 * @param unverifiedEvidence evidence items of the model that were removed because they could not be verified
 * @param title              one-line incident title
 * @param ownership          the team that most likely has to fix the failure
 * @param resolutionSteps    what the responsible team does, in order
 * @param otherTeamChecks    what another team has to confirm or provide
 * @param verificationSteps  how to confirm that the fix worked
 * @param expectedOutcome    what happens once the fix is applied
 * @param missingInformation what the evidence does not show; filled whenever owner or cause is uncertain
 * @param nextDiagnosticAction what to look at next when the cause is not proven
 * @param removedCommands    commands of the model that were removed as unsafe or naming resources the evidence lacks
 */
public record AgentReport(
        String summary,
        String rootCause,
        String failureCategory,
        BigDecimal confidence,
        List<Evidence> evidence,
        List<String> recommendedActions,
        int unverifiedEvidence,
        String title,
        Ownership ownership,
        List<Step> resolutionSteps,
        List<TeamCheck> otherTeamChecks,
        List<Step> verificationSteps,
        String expectedOutcome,
        List<String> missingInformation,
        String nextDiagnosticAction,
        int removedCommands) {

    /** @param step number of the investigation step whose tool result contains the observation */
    public record Evidence(int step, String observation) {
    }

    /** @param reason why this team, based on the evidence */
    public record Ownership(Team team, OwnershipConfidence confidence, String reason) {
    }

    /** @param command shell command or configuration to apply; null when the step needs none */
    public record Step(String action, String command) {
    }

    /** @param request what that team has to confirm or provide */
    public record TeamCheck(Team team, String request) {
    }

    public enum Team {
        /** Application code, tests, build files and Dockerfile of the repository. */
        DEVELOPER,
        /** Cluster, namespaces, RBAC, quotas, registries, secrets and network. */
        DEVOPS_PLATFORM,
        /** Tekton pipelines, tasks, parameters, workspaces and triggers. */
        CICD_ENGINEER,
        UNKNOWN
    }

    public enum OwnershipConfidence {
        HIGH, MEDIUM, LOW
    }
}
