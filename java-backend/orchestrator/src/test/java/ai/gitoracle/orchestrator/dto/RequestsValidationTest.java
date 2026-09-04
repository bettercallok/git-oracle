package ai.gitoracle.orchestrator.dto;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * H6: every request body in this module was a raw Map read with getOrDefault
 * and unchecked casts, so a missing field became a silent default and a wrong
 * type became a 500.
 *
 * The most important tests here are the ones asserting that EXISTING clients
 * still validate — the plan's own warning about this change is "clients sending
 * loose fields get 400", so the exact payloads the dashboard, CLI, and eval
 * harness send are pinned against the new constraints.
 */
class RequestsValidationTest {

    private static Validator validator;

    @BeforeAll
    static void setUp() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            validator = factory.getValidator();
        }
    }

    private static Set<String> violations(Object bean) {
        return validator.validate(bean).stream()
            .map(v -> v.getPropertyPath().toString())
            .collect(Collectors.toSet());
    }

    // ── Real client payloads must keep working ──────────────────────────────

    @Test
    void theDashboardsTriggerPayloadIsAccepted() {
        // FixCommand.tsx sends all five fields, with targetRepo/branch often "".
        var request = new Requests.TriggerFix(
            "https://github.com/o/r", "it crashes", "", "", true);

        assertThat(violations(request)).isEmpty();
        assertThat(request.targetRepoOrEmpty()).isEmpty();
        assertThat(request.branchOrEmpty()).isEmpty();
        assertThat(request.investigateFirstOrDefault()).isTrue();
    }

    @Test
    void theCommitExplorersTriggerPayloadIsAccepted() {
        // CommitDetail.tsx omits branch and investigateFirst entirely.
        var request = new Requests.TriggerFix(
            "https://github.com/o/r", "fix this commit", "o/r", null, null);

        assertThat(violations(request)).isEmpty();
        // Absent investigateFirst must still mean "run the full pipeline".
        assertThat(request.investigateFirstOrDefault()).isTrue();
        assertThat(request.branchOrEmpty()).isEmpty();
    }

    @Test
    void theEvalHarnessesMinimalTriggerPayloadIsAccepted() {
        // run_evals.py sends only repoUrl and issueDescription.
        var request = new Requests.TriggerFix(
            "file:///tmp/gitoracle-eval-repo", "seeded bug", null, null, null);

        assertThat(violations(request)).isEmpty();
    }

    @Test
    void investigateFirstFalseIsHonoured() {
        var request = new Requests.TriggerFix("https://github.com/o/r", "x", null, null, false);

        assertThat(request.investigateFirstOrDefault()).isFalse();
    }

    @Test
    void theAgentsPromptVersionPayloadIsAccepted() {
        // shared/prompt_registry.py sends {"agentName": ..., "version": int}.
        assertThat(violations(new Requests.RecordPromptVersion("fixer", 3))).isEmpty();
    }

    // ── TriggerFix constraints ──────────────────────────────────────────────

    @Test
    void triggerRequiresRepoUrlAndIssueDescription() {
        assertThat(violations(new Requests.TriggerFix(null, null, null, null, null)))
            .containsExactlyInAnyOrder("repoUrl", "issueDescription");
    }

    @Test
    void triggerRejectsAWhitespaceOnlyIssueDescription() {
        // The old check was `issue == null`, so "   " created a job whose entire
        // instruction was whitespace.
        assertThat(violations(new Requests.TriggerFix("https://github.com/o/r", "   ", null, null, null)))
            .containsExactly("issueDescription");
    }

    @Test
    void triggerBoundsFreeTextLength() {
        // issueDescription flows into an LLM prompt, a Kafka payload, and a DB
        // column; nothing bounded it before.
        var huge = new Requests.TriggerFix(
            "https://github.com/o/r", "x".repeat(20_001), null, null, null);

        assertThat(violations(huge)).containsExactly("issueDescription");
    }

    @Test
    void triggerBoundsRepoUrlLength() {
        var request = new Requests.TriggerFix(
            "https://github.com/" + "a".repeat(600), "bug", null, null, null);

        assertThat(violations(request)).containsExactly("repoUrl");
    }

    // ── ResolveEscalation: the silent-reject bug ────────────────────────────

    @Test
    void resolveAcceptsOnlyApproveOrReject() {
        assertThat(violations(new Requests.ResolveEscalation("approve"))).isEmpty();
        assertThat(violations(new Requests.ResolveEscalation("reject"))).isEmpty();
    }

    @Test
    void resolveRejectsAnythingElse() {
        // Previously ANY value that wasn't "approve" fell through to REJECTED —
        // so "Approve", "aprove", or a missing field silently rejected an
        // escalation a human meant to approve.
        assertThat(violations(new Requests.ResolveEscalation("Approve"))).containsExactly("action");
        assertThat(violations(new Requests.ResolveEscalation("aprove"))).containsExactly("action");
        assertThat(violations(new Requests.ResolveEscalation("delete"))).containsExactly("action");
        assertThat(violations(new Requests.ResolveEscalation(null))).containsExactly("action");
    }

    // ── SaveEval: the fabricated-zero bug ──────────────────────────────────

    @Test
    void saveEvalRequiresItsNumbersRatherThanDefaultingThemToZero() {
        // A missing accuracy used to be stored as 0.0 — indistinguishable from a
        // real run that genuinely scored zero, and it drags down any average
        // computed over the table.
        assertThat(violations(new Requests.SaveEval("v1", null, null, null)))
            .containsExactlyInAnyOrder("accuracy", "avgLatencyMs", "casesTotal");
    }

    @Test
    void saveEvalRangeChecksAccuracy() {
        assertThat(violations(new Requests.SaveEval("v1", 1.5, 10, 5))).containsExactly("accuracy");
        assertThat(violations(new Requests.SaveEval("v1", -0.1, 10, 5))).containsExactly("accuracy");
        assertThat(violations(new Requests.SaveEval("v1", 0.75, 10, 5))).isEmpty();
    }

    @Test
    void saveEvalRejectsNegativeCounts() {
        assertThat(violations(new Requests.SaveEval("v1", 0.5, -1, 5))).containsExactly("avgLatencyMs");
        assertThat(violations(new Requests.SaveEval("v1", 0.5, 10, -1))).containsExactly("casesTotal");
    }

    @Test
    void saveEvalDefaultsOnlyTheDatasetVersion() {
        assertThat(new Requests.SaveEval(null, 0.5, 1, 1).goldenDatasetVersionOrDefault()).isEqualTo("v1");
        assertThat(new Requests.SaveEval("", 0.5, 1, 1).goldenDatasetVersionOrDefault()).isEqualTo("v1");
        assertThat(new Requests.SaveEval("v9", 0.5, 1, 1).goldenDatasetVersionOrDefault()).isEqualTo("v9");
    }

    // ── The rest ────────────────────────────────────────────────────────────

    @Test
    void createJobRequiresARepoUrl() {
        assertThat(violations(new Requests.CreateJob(null))).containsExactly("repoUrl");
        assertThat(violations(new Requests.CreateJob("  "))).containsExactly("repoUrl");
        assertThat(violations(new Requests.CreateJob("https://github.com/o/r"))).isEmpty();
    }

    @Test
    void feedbackRequiresNonBlankInstructions() {
        assertThat(violations(new Requests.Feedback(null))).containsExactly("instructions");
        assertThat(violations(new Requests.Feedback(""))).containsExactly("instructions");
        assertThat(violations(new Requests.Feedback("please use a null check"))).isEmpty();
    }

    @Test
    void registerTenantRequiresANameAndBoundsIt() {
        assertThat(violations(new Requests.RegisterTenant(null))).containsExactly("name");
        // org_name is a varchar column — an over-long value was a constraint
        // violation surfaced as a 500.
        assertThat(violations(new Requests.RegisterTenant("a".repeat(256)))).containsExactly("name");
        assertThat(violations(new Requests.RegisterTenant("acme-corp"))).isEmpty();
    }

    @Test
    void activatePromptRequiresANumericVersion() {
        assertThat(violations(new Requests.ActivatePrompt("3"))).isEmpty();
        assertThat(violations(new Requests.ActivatePrompt("latest"))).containsExactly("version");
        assertThat(violations(new Requests.ActivatePrompt("-1"))).containsExactly("version");
        assertThat(violations(new Requests.ActivatePrompt(null))).containsExactly("version");
    }

    @Test
    void recordPromptVersionRejectsMissingOrNonPositiveValues() {
        assertThat(violations(new Requests.RecordPromptVersion(null, 1))).containsExactly("agentName");
        assertThat(violations(new Requests.RecordPromptVersion("fixer", null))).containsExactly("version");
        assertThat(violations(new Requests.RecordPromptVersion("fixer", 0))).containsExactly("version");
    }

    // ── AnalyzeCommit: bounded because it is replayed into an LLM prompt ────

    @Test
    void analyzeAcceptsTheDashboardsPayload() {
        var request = new Requests.AnalyzeCommit(
            "what changed?",
            List.of(new Requests.ChatMessage("user", "hi"),
                    new Requests.ChatMessage("assistant", "hello")));

        assertThat(validator.validate(request)).isEmpty();
    }

    @Test
    void analyzeAcceptsAnAbsentChatHistory() {
        var request = new Requests.AnalyzeCommit("what changed?", null);

        assertThat(validator.validate(request)).isEmpty();
        assertThat(request.chatHistoryOrEmpty()).isEmpty();
    }

    @Test
    void analyzeRequiresAQuestion() {
        assertThat(violations(new Requests.AnalyzeCommit("  ", List.of()))).containsExactly("question");
    }

    @Test
    void analyzeBoundsChatHistoryLength() {
        // Unbounded history replayed into a prompt is a way to run up token cost
        // on someone else's budget.
        var many = java.util.stream.IntStream.range(0, 51)
            .mapToObj(i -> new Requests.ChatMessage("user", "msg " + i))
            .toList();

        assertThat(violations(new Requests.AnalyzeCommit("q", many))).containsExactly("chatHistory");
    }

    @Test
    void analyzeConstrainsChatMessageRole() {
        assertThat(violations(new Requests.ChatMessage("root", "x"))).containsExactly("role");
        assertThat(violations(new Requests.ChatMessage("user", "x"))).isEmpty();
    }
}
