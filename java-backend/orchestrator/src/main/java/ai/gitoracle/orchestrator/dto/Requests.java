package ai.gitoracle.orchestrator.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Typed, validated request bodies for the orchestrator's HTTP API.
 *
 * <h2>What these replace</h2>
 * Every endpoint here took a raw {@code Map<String, Object>} and read it with
 * {@code get} / {@code getOrDefault} / an unchecked cast. Three consequences,
 * all of them observed in the code being replaced:
 *
 * <ul>
 *   <li><b>A missing field became a null or a silent default.</b>
 *       {@code saveEval} defaulted accuracy, latency, and case count to 0 when
 *       the caller sent a non-number, so a malformed eval submission was
 *       recorded as a real run scoring zero rather than rejected.</li>
 *   <li><b>A wrong type became a 500.</b> An unchecked cast such as
 *       {@code (String) request.get("repoUrl")} throws ClassCastException on
 *       {@code {"repoUrl": 42}}, which surfaces as an opaque server error for
 *       what is plainly a client mistake.</li>
 *   <li><b>There was no length bound anywhere.</b> Free-text fields flow into
 *       LLM prompts, Kafka payloads, and database columns; nothing stopped a
 *       multi-megabyte {@code issueDescription} from being accepted and
 *       forwarded.</li>
 * </ul>
 *
 * <h2>Deliberately permissive where clients already exist</h2>
 * Unknown properties are still ignored (Spring Boot's default). The CLI posts
 * {@code commitHash} and {@code jobType} to {@code /jobs}, which that endpoint
 * has never read; failing on unknown fields would break it for no security
 * benefit, since an ignored field cannot influence anything.
 *
 * Optional fields that existing callers send as empty strings are typed as
 * plain Strings with a size bound rather than {@code @NotBlank} — the dashboard
 * sends {@code targetRepo: ""} and {@code branch: ""} routinely, and the
 * downstream code already treats empty as "not specified".
 *
 * <h2>Why not {@code @Pattern} on repo URLs and branches</h2>
 * {@link ai.gitoracle.core.security.RepoRefValidator} already enforces those,
 * with a configurable host allowlist and the specific injection rules that
 * matter for git operands. Restating a weaker version of the same rule as an
 * annotation would create two places to fix when one changes. These records
 * bound size and require presence; the validator remains the authority on shape.
 */
public final class Requests {

    private Requests() {}

    /** POST /api/v1/jobs — the CLI's entry point. */
    public record CreateJob(
        @NotBlank(message = "repoUrl is required")
        @Size(max = 512, message = "repoUrl must be at most 512 characters")
        String repoUrl
    ) {}

    /** POST /api/v1/trigger — the dashboard's "Ask GitOracle to fix". */
    public record TriggerFix(
        @NotBlank(message = "repoUrl is required")
        @Size(max = 512, message = "repoUrl must be at most 512 characters")
        String repoUrl,

        @NotBlank(message = "issueDescription is required")
        @Size(max = 20_000, message = "issueDescription must be at most 20000 characters")
        String issueDescription,

        // Optional. The dashboard sends "" when the user leaves it empty, and
        // the CLI sends a real value; both must keep working.
        @Size(max = 256, message = "targetRepo must be at most 256 characters")
        String targetRepo,

        @Size(max = 255, message = "branch must be at most 255 characters")
        String branch,

        // Optional, defaults to true when absent — the eval harness and the
        // commit explorer both omit it entirely.
        Boolean investigateFirst
    ) {
        public String targetRepoOrEmpty() {
            return targetRepo == null ? "" : targetRepo;
        }

        public String branchOrEmpty() {
            return branch == null ? "" : branch;
        }

        public boolean investigateFirstOrDefault() {
            return !Boolean.FALSE.equals(investigateFirst);
        }
    }

    /** POST /api/v1/jobs/{jobId}/feedback — dashboard "Regenerate Fix". */
    public record Feedback(
        @NotBlank(message = "instructions is required")
        @Size(max = 20_000, message = "instructions must be at most 20000 characters")
        String instructions
    ) {}

    /** POST /api/v1/escalations/{id}/resolve. */
    public record ResolveEscalation(
        // Constrained to the two values the handler actually branches on.
        // Previously anything that was not "approve" silently meant "reject" —
        // including a typo, and including an absent field.
        @NotNull(message = "action is required")
        @Pattern(regexp = "approve|reject", message = "action must be 'approve' or 'reject'")
        String action
    ) {}

    /** POST /api/v1/evals — written by the eval harness. */
    public record SaveEval(
        @Size(max = 64, message = "goldenDatasetVersion must be at most 64 characters")
        String goldenDatasetVersion,

        // Required and range-checked. These were silently coerced to 0 when
        // absent or non-numeric, which recorded a fabricated result rather than
        // rejecting a malformed submission.
        @NotNull(message = "accuracy is required")
        @DecimalMin(value = "0.0", message = "accuracy must be between 0.0 and 1.0")
        @DecimalMax(value = "1.0", message = "accuracy must be between 0.0 and 1.0")
        Double accuracy,

        @NotNull(message = "avgLatencyMs is required")
        @Min(value = 0, message = "avgLatencyMs must not be negative")
        Integer avgLatencyMs,

        @NotNull(message = "casesTotal is required")
        @Min(value = 0, message = "casesTotal must not be negative")
        Integer casesTotal
    ) {
        public String goldenDatasetVersionOrDefault() {
            return goldenDatasetVersion == null || goldenDatasetVersion.isBlank() ? "v1" : goldenDatasetVersion;
        }
    }

    /** POST /api/v1/jobs/{jobId}/prompt-version — written by the Python agents. */
    public record RecordPromptVersion(
        @NotBlank(message = "agentName is required")
        @Size(max = 64, message = "agentName must be at most 64 characters")
        String agentName,

        @NotNull(message = "version is required")
        @Min(value = 1, message = "version must be a positive integer")
        Integer version
    ) {}

    /** POST /api/v1/admin/tenants. */
    public record RegisterTenant(
        @NotBlank(message = "name is required")
        @Size(max = 255, message = "name must be at most 255 characters")
        String name
    ) {}

    /** POST /api/v1/admin/prompts/{agent}/activate. */
    public record ActivatePrompt(
        @NotBlank(message = "version is required")
        @Pattern(regexp = "\\d{1,9}", message = "version must be a positive integer")
        String version
    ) {}

    /** POST /api/v1/commits/{sha}/analyze. */
    public record AnalyzeCommit(
        @NotBlank(message = "question is required")
        @Size(max = 4_000, message = "question must be at most 4000 characters")
        String question,

        // Bounded: this is replayed verbatim into an LLM prompt, so an
        // unbounded history is a way to run up token cost on someone else's
        // budget.
        @Size(max = 50, message = "chatHistory must contain at most 50 messages")
        List<ChatMessage> chatHistory
    ) {
        public List<ChatMessage> chatHistoryOrEmpty() {
            return chatHistory == null ? List.of() : chatHistory;
        }
    }

    public record ChatMessage(
        @NotBlank(message = "chatHistory[].role is required")
        @Pattern(regexp = "user|assistant|system", message = "chatHistory[].role must be user, assistant, or system")
        String role,

        @NotNull(message = "chatHistory[].content is required")
        @Size(max = 10_000, message = "chatHistory[].content must be at most 10000 characters")
        String content
    ) {}
}
