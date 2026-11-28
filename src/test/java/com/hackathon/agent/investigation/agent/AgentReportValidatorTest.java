package com.hackathon.agent.investigation.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.hackathon.agent.evidence.sanitizer.LogSanitizer;
import com.hackathon.agent.investigation.agent.AgentDecision.ProposedEvidence;
import com.hackathon.agent.investigation.agent.AgentDecision.ProposedReport;
import com.hackathon.agent.investigation.agent.AgentDecision.ProposedStep;
import com.hackathon.agent.investigation.agent.AgentDecision.ProposedTeamCheck;
import com.hackathon.agent.investigation.model.AgentReport;
import com.hackathon.agent.investigation.model.AgentReport.OwnershipConfidence;
import com.hackathon.agent.investigation.model.AgentReport.Step;
import com.hackathon.agent.investigation.model.AgentReport.Team;
import com.hackathon.agent.investigation.model.AgentReport.TeamCheck;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Ownership, resolution and command checks of the final report. */
class AgentReportValidatorTest {

    private static final String FORBIDDEN = "deployments.apps is forbidden: User \"system:serviceaccount:ci:pipeline-deployer\" "
            + "cannot create resource \"deployments\" in API group \"apps\" in the namespace \"ci\"";
    /** Tool results: the TaskRun log and the service account with its bindings. */
    private static final Map<Integer, String> RESULTS = Map.of(
            1, "{\"log\":\"" + FORBIDDEN.replace("\"", "\\\"") + "\"}",
            2, "{\"serviceAccount\":\"pipeline-deployer\",\"namespace\":\"ci\",\"roleBindings\":[{\"role\":\"pipeline-reader\"}]}");

    private static final String ROLE = """
            apiVersion: rbac.authorization.k8s.io/v1
            kind: Role
            metadata:
              name: <role-name>
              namespace: ci
            rules:
              - apiGroups: ["apps"]
                resources: ["deployments"]
                verbs: ["get", "create", "update", "patch"]
            ---
            apiVersion: rbac.authorization.k8s.io/v1
            kind: RoleBinding
            metadata:
              name: <role-name>-binding
              namespace: ci
            subjects:
              - kind: ServiceAccount
                name: pipeline-deployer
                namespace: ci
            roleRef:
              kind: Role
              name: <role-name>
              apiGroup: rbac.authorization.k8s.io""";

    private final AgentDecisionParser parser = new AgentDecisionParser();
    private final AgentReportValidator validator = new AgentReportValidator(new LogSanitizer());

    @Test
    void permissionFailureIsAssignedToThePlatformTeamWithAScopedFixAndAVerification() {
        AgentReport report = validator.validate(rbacReport(List.of(
                new ProposedStep("Grant the service account create on Deployments in namespace ci with a Role and RoleBinding", ROLE),
                new ProposedStep("Apply the manifest", "kubectl apply -n ci -f deployer-role.yaml"))), RESULTS, "{}");

        assertThat(report.title()).isEqualTo("Pipeline service account cannot create Deployments in ci");
        assertThat(report.ownership().team()).isEqualTo(Team.DEVOPS_PLATFORM);
        assertThat(report.ownership().confidence()).isEqualTo(OwnershipConfidence.HIGH);
        assertThat(report.ownership().reason()).contains("RBAC");
        assertThat(report.resolutionSteps()).extracting(Step::command).containsExactly(ROLE, "kubectl apply -n ci -f deployer-role.yaml");
        assertThat(report.otherTeamChecks()).containsExactly(
                new TeamCheck(Team.DEVELOPER, "Confirm that the deploy task should run as pipeline-deployer"));
        assertThat(report.verificationSteps()).extracting(Step::command).containsExactly(
                "kubectl auth can-i create deployments -n ci --as=system:serviceaccount:ci:pipeline-deployer", null);
        assertThat(report.expectedOutcome()).isEqualTo("The deploy task creates the Deployment and the PipelineRun succeeds");
        assertThat(report.missingInformation()).isEmpty();
        assertThat(report.nextDiagnosticAction()).isNull();
        assertThat(report.removedCommands()).isZero();
        assertThat(report.recommendedActions()).containsExactly(
                "Grant the service account create on Deployments in namespace ci with a Role and RoleBinding", "Apply the manifest");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "kubectl delete pod demo-app -n ci",
            "kubectl create clusterrolebinding x --clusterrole=cluster-admin --serviceaccount=ci:pipeline-deployer",
            "kubectl apply --force -f role.yaml -n ci",
            "kubectl get pods --insecure-skip-tls-verify -n ci",
            "rules:\n  - apiGroups: [\"*\"]\n    resources: [\"*\"]\n    verbs: [\"*\"]",
            "kubectl auth can-i create deployments -n production --as=system:serviceaccount:ci:pipeline-deployer",
            "kubectl auth can-i create deployments -n ci --as=system:serviceaccount:ci:default-builder",
            "kubectl describe deployment payment-api -n ci",
            "kubectl rollout status deployment/payment-api -n ci",
            "tkn pipelinerun logs demo-app-build-zzzzz -n ci"})
    void unsafeCommandsAndCommandsNamingUnseenResourcesAreRemoved(String command) {
        AgentReport report = validator.validate(rbacReport(List.of(new ProposedStep("Do it", command))), RESULTS, "{}");

        assertThat(report.resolutionSteps()).containsExactly(new Step("Do it", null));
        assertThat(report.removedCommands()).isEqualTo(1);
    }

    @Test
    void namesFromTheKnownContextCountAsSeen() {
        String command = "tkn pipelinerun logs demo-app-build-bb7hj -n ci";

        assertThat(validator.validate(rbacReport(List.of(new ProposedStep("Read the run", command))), RESULTS,
                "{\"pipelineRun\":\"demo-app-build-bb7hj\"}").resolutionSteps()).extracting(Step::command).containsExactly(command);
    }

    @Test
    void anUnprovenCauseHasALowConfidenceOwnerAndSaysWhatIsMissing() {
        ProposedReport proposed = new ProposedReport("Unclear", "The logs were not available", "UNKNOWN", new BigDecimal("0.2"),
                List.of(), List.of(), null, "DEVELOPER", "HIGH", "Probably the code", List.of(), List.of(), List.of(), null,
                List.of(), null);

        AgentReport report = validator.validate(proposed, Map.of(), "{}");

        assertThat(report.ownership().team()).isEqualTo(Team.DEVELOPER);
        assertThat(report.ownership().confidence()).isEqualTo(OwnershipConfidence.LOW);
        assertThat(report.missingInformation()).containsExactly(AgentReportValidator.DEFAULT_MISSING);
        assertThat(report.nextDiagnosticAction()).isEqualTo(AgentReportValidator.DEFAULT_NEXT_ACTION);
        assertThat(report.title()).isEqualTo("Unclear");
    }

    @Test
    void theMissingInformationOfTheModelIsKeptWhenItGaveIt() {
        ProposedReport proposed = new ProposedReport("s", "r", "PERMISSION_DENIED", new BigDecimal("0.9"),
                List.of(new ProposedEvidence(1, "cannot create resource")), List.of(), "t", "unknown", "MEDIUM", null,
                List.of(), List.of(), List.of(), null, List.of("Which team manages the ci namespace"), "Ask who owns ci");

        AgentReport report = validator.validate(proposed, RESULTS, "{}");

        assertThat(report.ownership().team()).isEqualTo(Team.UNKNOWN);
        assertThat(report.ownership().confidence()).isEqualTo(OwnershipConfidence.LOW);
        assertThat(report.ownership().reason()).isEqualTo(AgentReportValidator.DEFAULT_MISSING_OWNER);
        assertThat(report.missingInformation()).containsExactly("Which team manages the ci namespace");
        assertThat(report.nextDiagnosticAction()).isEqualTo("Ask who owns ci");
    }

    @ParameterizedTest
    @CsvSource({
            "DevOps / Platform, HIGH, 0.9, DEVOPS_PLATFORM, HIGH",
            "CI/CD Engineer, MEDIUM, 0.9, CICD_ENGINEER, MEDIUM",
            "developer, HIGH, 0.4, DEVELOPER, MEDIUM",
            "QA, HIGH, 0.9, UNKNOWN, LOW",
            "developer, VERY_HIGH, 0.9, DEVELOPER, LOW"})
    void normalizesTeamAndOwnershipConfidence(String team, String confidence, String reportConfidence, Team expectedTeam,
                                              OwnershipConfidence expectedConfidence) {
        ProposedReport proposed = new ProposedReport("s", "r", "COMPILATION", new BigDecimal(reportConfidence),
                List.of(new ProposedEvidence(1, "cannot create resource")), List.of(), "t", team, confidence, "why",
                List.of(), List.of(), List.of(), null, List.of(), null);

        AgentReport.Ownership ownership = validator.validate(proposed, RESULTS, "{}").ownership();

        assertThat(ownership.team()).isEqualTo(expectedTeam);
        assertThat(ownership.confidence()).isEqualTo(expectedConfidence);
    }

    @Test
    void commandsAreRedacted() {
        AgentReport report = validator.validate(rbacReport(List.of(
                new ProposedStep("Log in", "docker login -u ci --password=hunter2secret registry.example.com"))), RESULTS, "{}");

        assertThat(report.toString()).doesNotContain("hunter2secret");
    }

    @Test
    void parsesTheNewReportFieldsAndToleratesTheirAbsence() {
        AgentDecision decision = parser.parse("""
                {"reasoning": "done", "action": "FINAL_REPORT", "toolName": null, "resourceName": null, "namespace": null,
                 "report": {"summary": "s", "rootCause": "r", "failureCategory": "PERMISSION_DENIED", "confidence": 0.9,
                  "evidence": [], "recommendedActions": [], "title": "Missing permission",
                  "ownership": {"team": "DEVOPS_PLATFORM", "confidence": "HIGH", "reason": "RBAC"},
                  "resolutionSteps": [{"action": "Grant it", "command": "\\nkubectl apply -f role.yaml\\n  "}, {"action": "", "command": "x"},
                                      {"action": "Rerun", "command": null}],
                  "otherTeamChecks": [{"team": "DEVELOPER", "request": "Confirm the service account"}, {"team": "X"}],
                  "verificationSteps": [{"action": "Check", "command": "kubectl auth can-i"}],
                  "expectedOutcome": "It works", "missingInformation": [], "nextDiagnosticAction": null}}""");

        ProposedReport report = decision.report();
        assertThat(report.title()).isEqualTo("Missing permission");
        assertThat(report.ownerTeam()).isEqualTo("DEVOPS_PLATFORM");
        assertThat(report.ownershipConfidence()).isEqualTo("HIGH");
        assertThat(report.resolutionSteps()).containsExactly(new ProposedStep("Grant it", "kubectl apply -f role.yaml"),
                new ProposedStep("Rerun", null));
        assertThat(report.otherTeamChecks()).containsExactly(new ProposedTeamCheck("DEVELOPER", "Confirm the service account"));
        assertThat(report.verificationSteps()).hasSize(1);
        assertThat(report.expectedOutcome()).isEqualTo("It works");

        ProposedReport old = parser.parse("""
                {"action": "FINAL_REPORT", "report": {"rootCause": "r", "confidence": 0.5}}""").report();
        assertThat(old.ownerTeam()).isNull();
        assertThat(old.resolutionSteps()).isEmpty();
        assertThat(old.missingInformation()).isEmpty();
    }

    @Test
    void schemaRequiresTheNewFields() {
        @SuppressWarnings("unchecked")
        Map<String, Object> report = (Map<String, Object>) ((Map<String, Object>) AgentDecisionParser.schema().get("properties")).get("report");

        assertThat((List<String>) report.get("required")).contains("title", "ownership", "resolutionSteps", "otherTeamChecks",
                "verificationSteps", "expectedOutcome", "missingInformation", "nextDiagnosticAction");
    }

    private static ProposedReport rbacReport(List<ProposedStep> resolution) {
        return new ProposedReport(
                "The deploy task failed because its service account may not create Deployments.",
                "The pipeline runs as pipeline-deployer, which may only read PipelineRuns, so creating the Deployment was refused.",
                "PERMISSION_DENIED", new BigDecimal("0.9"),
                List.of(new ProposedEvidence(1, "cannot create resource \"deployments\" in API group \"apps\"")),
                List.of(),
                "Pipeline service account cannot create Deployments in ci",
                "DEVOPS_PLATFORM", "HIGH", "Service account permissions (RBAC) in the namespace are managed by the platform team",
                resolution,
                List.of(new ProposedTeamCheck("DEVELOPER", "Confirm that the deploy task should run as pipeline-deployer")),
                List.of(new ProposedStep("Check the permission",
                                "kubectl auth can-i create deployments -n ci --as=system:serviceaccount:ci:pipeline-deployer"),
                        new ProposedStep("Rerun the PipelineRun", null)),
                "The deploy task creates the Deployment and the PipelineRun succeeds",
                List.of(), null);
    }
}
