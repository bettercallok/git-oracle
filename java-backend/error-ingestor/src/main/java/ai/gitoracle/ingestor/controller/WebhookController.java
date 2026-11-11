package ai.gitoracle.ingestor.controller;

import ai.gitoracle.core.kafka.KafkaTopics;
import ai.gitoracle.core.model.postgres.Tenant;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import ai.gitoracle.ingestor.security.DeliveryReplayGuard;
import ai.gitoracle.ingestor.security.HmacVerifier;
import ai.gitoracle.ingestor.service.SemanticDedupService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/webhook")
public class WebhookController {

    private static final Logger logger = LoggerFactory.getLogger(WebhookController.class);
    private static final Pattern JOB_ID_PATTERN =
        Pattern.compile("<!--\\s*gitoracle:job_id:([0-9a-fA-F\\-]{36})\\s*-->");

    /**
     * GitHub's author_association values for people with write-level standing
     * on the repository. CONTRIBUTOR, FIRST_TIME_CONTRIBUTOR, FIRST_TIMER,
     * MANNEQUIN and NONE are all reachable by an outsider.
     */
    static final java.util.Set<String> TRUSTED_COMMENTER_ASSOCIATIONS =
        java.util.Set.of("OWNER", "MEMBER", "COLLABORATOR");

    private final SemanticDedupService dedupService;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final DeliveryReplayGuard replayGuard;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // Both were already defined in .env.example and read by nothing — anyone
    // who knew (or guessed) this endpoint's URL could inject an arbitrary
    // job. Fails closed to match TenantContextFilter/InternalAuthFilter's
    // established posture: an unconfigured secret refuses every request
    // rather than silently accepting them unverified.
    @Value("${gitoracle.github-webhook-secret:}")
    private String githubWebhookSecret;

    @Value("${GITHUB_INSTALLATION_ID:}")
    private String defaultInstallationId;

    @Value("${gitoracle.sentry-webhook-secret:}")
    private String sentryWebhookSecret;

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * The secret a tenant's Sentry webhook must be signed with. It used to be
     * one SENTRY_WEBHOOK_SECRET for everyone, with the tenant taken from the
     * URL, so whoever held it could file jobs under any tenant. Now each tenant
     * has its own (set via POST /api/v1/admin/tenants/{id}/sentry-secret); the
     * shared secret is accepted only for the default tenant, which keeps a
     * single-tenant install working unchanged. Unknown tenant or no secret:
     * empty, which HmacVerifier rejects.
     */
    String sentrySecretFor(UUID tenantId) {
        Tenant tenant = entityManager == null ? null : entityManager.find(Tenant.class, tenantId);
        if (tenant != null && tenant.getSentryWebhookSecret() != null && !tenant.getSentryWebhookSecret().isBlank()) {
            return tenant.getSentryWebhookSecret();
        }
        return Tenant.DEFAULT_ID.equals(tenantId) ? sentryWebhookSecret : "";
    }

    public WebhookController(SemanticDedupService dedupService, KafkaTemplate<String, Object> kafkaTemplate,
                              DeliveryReplayGuard replayGuard) {
        this.dedupService = dedupService;
        this.kafkaTemplate = kafkaTemplate;
        this.replayGuard = replayGuard;
    }

    /**
     * No installation in the payload (the App's own events always have one, but a plain
     * repository webhook doesn't) and the shared GITHUB_INSTALLATION_ID both mean the
     * default organisation, which keeps a single-operator install working.
     */
    @SuppressWarnings("unchecked")
    Optional<UUID> tenantForInstallation(Map<String, Object> payload) {
        Object id = payload.get("installation") instanceof Map<?, ?> installation ? installation.get("id") : null;
        if (id == null) return Optional.of(Tenant.DEFAULT_ID);
        String installationId = String.valueOf(id);
        if (entityManager != null) {
            List<UUID> tenants = entityManager.createQuery(
                    "SELECT t.id FROM Tenant t WHERE t.githubAppInstallationId = :installation", UUID.class)
                .setParameter("installation", installationId)
                .getResultList();
            if (!tenants.isEmpty()) return Optional.of(tenants.get(0));
        }
        return installationId.equals(defaultInstallationId) ? Optional.of(Tenant.DEFAULT_ID) : Optional.empty();
    }

    @PostMapping("/{tenantId}/sentry")
    public ResponseEntity<Void> sentryWebhook(
            @PathVariable UUID tenantId,
            @RequestBody byte[] rawBody,
            @RequestHeader(value = "X-Sentry-Signature", required = false) String sig) {

        if (!HmacVerifier.verify(rawBody, sentrySecretFor(tenantId), sig, "sha256=")) {
            logger.warn("Rejected Sentry webhook for tenant {}: invalid or missing X-Sentry-Signature.", tenantId);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        Map<String, Object> payload = parseBody(rawBody);
        if (payload == null) {
            return ResponseEntity.badRequest().build();
        }

        logger.info("Received Sentry webhook for tenant: {}", tenantId);
        dedupService.ingest(tenantId, payload);
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/github")
    public ResponseEntity<Void> githubWebhook(
            @RequestHeader(value = "X-GitHub-Event", required = false) String githubEvent,
            @RequestHeader(value = "X-GitHub-Delivery", required = false) String deliveryId,
            @RequestHeader(value = "X-Hub-Signature-256", required = false) String signature,
            @RequestBody byte[] rawBody) {

        if (!HmacVerifier.verify(rawBody, githubWebhookSecret, signature, "sha256=")) {
            logger.warn("Rejected GitHub webhook (event={}, delivery={}): invalid or missing X-Hub-Signature-256.",
                githubEvent, deliveryId);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (replayGuard.isReplay(deliveryId)) {
            logger.warn("Rejected GitHub webhook: delivery {} already processed within the replay window.", deliveryId);
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }

        Map<String, Object> payload = parseBody(rawBody);
        if (payload == null) {
            return ResponseEntity.badRequest().build();
        }

        // The delivery is signed, so its installation id can be trusted, and that's
        // what says whose repository this is. Anything unrecognised is dropped rather
        // than filed under the default organisation.
        UUID tenantId = tenantForInstallation(payload).orElse(null);
        if (tenantId == null) {
            logger.info("Ignoring GitHub webhook from an installation no organisation has connected (event={})", githubEvent);
            return ResponseEntity.accepted().build();
        }

        logger.info("Received GitHub webhook for tenant: {} (Event: {})", tenantId, githubEvent);

        // --- PR Review Comment (line-level comment on diff) ---
        if ("pull_request_review_comment".equals(githubEvent)) {
            return handleReviewComment(tenantId, payload, "pull_request_review_comment");
        }

        // --- Issue Comment (general PR/issue comment) ---
        if ("issue_comment".equals(githubEvent)) {
            String action = (String) payload.get("action");
            if ("created".equals(action)) {
                return handleReviewComment(tenantId, payload, "issue_comment");
            }
            return ResponseEntity.accepted().build();
        }

        // --- Pull request closed/merged: the outcome of a fix GitOracle proposed ---
        if ("pull_request".equals(githubEvent)) {
            return handlePullRequestOutcome(payload);
        }

        // --- Pull request review (approval counts as positive feedback) ---
        if ("pull_request_review".equals(githubEvent)) {
            return handlePullRequestReview(payload);
        }

        // --- GitHub Actions workflow_run failure ---
        if ("workflow_run".equals(githubEvent)) {
            return handleWorkflowRun(tenantId, payload);
        }

        // Everything else (push, ping, installation, ...) carries no failure to fix.
        // This used to fall through to dedupService.ingest, which turned every such
        // delivery into a junk "unknown/repo" job. Only a failed workflow_run
        // starts a job from GitHub.
        logger.info("Ignoring GitHub event '{}'", githubEvent);
        return ResponseEntity.accepted().build();
    }

    /**
     * Starts a job for a failed GitHub Actions run. Callers have already
     * authenticated the delivery: by its signature here, or as an admin in
     * {@link WebhookSimulatorController}.
     */
    @SuppressWarnings("unchecked")
    ResponseEntity<Void> handleWorkflowRun(UUID tenantId, Map<String, Object> payload) {
        Object action = payload.get("action");
        Map<String, Object> workflowRun = (Map<String, Object>) payload.get("workflow_run");
        Map<String, Object> repository = (Map<String, Object>) payload.get("repository");

        if ("completed".equals(action) && workflowRun != null && repository != null) {
            String conclusion = (String) workflowRun.get("conclusion");
            if ("failure".equals(conclusion)) {
                if (!(repository.get("full_name") instanceof String repoName) || !(workflowRun.get("id") instanceof Number id)) {
                    return ResponseEntity.badRequest().build();
                }
                String runId = String.valueOf(id.longValue());
                logger.info("Parsed failed workflow_run for repository: {} (Run ID: {})", repoName, runId);
                Map<String, Object> internalPayload = Map.of(
                    "repo",       "https://github.com/" + repoName,
                    "error_id",   "run-" + runId,
                    "stacktrace", "GitHub Actions Run Failed: " + runId
                );
                dedupService.ingest(tenantId, internalPayload);
            } else {
                logger.info("Ignoring successful workflow_run.");
            }
        }
        return ResponseEntity.accepted().build();
    }

    /**
     * Handles both issue_comment and pull_request_review_comment events.
     * Extracts the gitoracle job ID from the PR body (embedded as an HTML comment by the GitHub Bot),
     * then publishes a job.events.review.received event to Kafka for the Orchestrator.
     */
    @SuppressWarnings("unchecked")
    private ResponseEntity<Void> handleReviewComment(UUID tenantId, Map<String, Object> payload, String eventType) {
        // Extract comment body
        Map<String, Object> comment = (Map<String, Object>) payload.get("comment");
        if (comment == null) return ResponseEntity.accepted().build();

        String commentBody = (String) comment.get("body");
        if (commentBody == null || commentBody.isBlank()) return ResponseEntity.accepted().build();

        // Who may steer a fix. A comment's text becomes the Fixer's
        // human_instructions, and the regenerated patch is pushed onto the PR
        // branch with the App's credentials. Anyone with a GitHub account can
        // comment on a public repository's PR, so without this check any
        // passer-by could instruct GitOracle to rewrite a PR a maintainer had
        // already reviewed. Only people GitHub itself reports as having a
        // standing relationship to the repository count; bots (GitOracle's own
        // "regenerated this fix" comments included) never do.
        Map<String, Object> commentSender = (Map<String, Object>) payload.get("sender");
        if (commentSender != null && "Bot".equals(commentSender.get("type"))) {
            logger.info("Ignoring {} from a bot account.", eventType);
            return ResponseEntity.accepted().build();
        }
        Object association = comment.get("author_association");
        if (!(association instanceof String a) || !TRUSTED_COMMENTER_ASSOCIATIONS.contains(a)) {
            logger.info("Ignoring {} from a commenter with author_association={} — only {} may request a regeneration.",
                eventType, association, TRUSTED_COMMENTER_ASSOCIATIONS);
            return ResponseEntity.accepted().build();
        }

        // Extract PR body (contains the embedded job_id marker) and the PR's own
        // URL. The marker alone proves nothing: every GitOracle PR body shows
        // its job id publicly, so anyone can paste it into an issue or PR of
        // their own. The orchestrator therefore only acts when prUrl is the
        // job's recorded PR.
        String jobId = null;
        String prUrl = null;

        // Try pull_request block first (review comment)
        Map<String, Object> pr = (Map<String, Object>) payload.get("pull_request");
        if (pr != null) {
            String prBody = (String) pr.get("body");
            if (prBody != null) {
                Matcher m = JOB_ID_PATTERN.matcher(prBody);
                if (m.find()) {
                    jobId = m.group(1);
                    prUrl = (String) pr.get("html_url");
                }
            }
        }
        // Fallback: try issue block (issue_comment on PR). Only a PR's issue:
        // a plain issue carrying a copied marker is not a GitOracle PR.
        if (jobId == null) {
            Map<String, Object> issue = (Map<String, Object>) payload.get("issue");
            if (issue != null && issue.get("pull_request") != null) {
                String issueBody = (String) issue.get("body");
                if (issueBody != null) {
                    Matcher m = JOB_ID_PATTERN.matcher(issueBody);
                    if (m.find()) {
                        jobId = m.group(1);
                        prUrl = (String) issue.get("html_url");
                    }
                }
            }
        }

        // Extract repo
        Map<String, Object> repository = (Map<String, Object>) payload.get("repository");
        String repoFullName = repository != null ? (String) repository.get("full_name") : "unknown/unknown";

        // Extract commenter login
        Map<String, Object> sender = (Map<String, Object>) payload.get("sender");
        String commenter = sender != null ? (String) sender.get("login") : "unknown";

        if (jobId != null) {
            logger.info("Review comment on GitOracle PR — job: {} commenter: {} comment: '{}'",
                        jobId, commenter, commentBody.substring(0, Math.min(80, commentBody.length())));

            Map<String, Object> reviewEvent = new HashMap<>();
            reviewEvent.put("job_id",      jobId);
            reviewEvent.put("comment",     commentBody);
            reviewEvent.put("commenter",   commenter);
            reviewEvent.put("repo",        "https://github.com/" + repoFullName);
            reviewEvent.put("event_type",  eventType);
            reviewEvent.put("tenant_id",   tenantId.toString());
            reviewEvent.put("pr_url",      prUrl != null ? prUrl : "");

            kafkaTemplate.send(KafkaTopics.JOB_REVIEW_RECEIVED, reviewEvent);
        } else {
            logger.info("Review comment received but no GitOracle job ID found in PR body. Ignoring.");
        }

        return ResponseEntity.accepted().build();
    }

    /**
     * Records what happened to a PR GitOracle opened.
     *
     * This is the producer that "github-pr-events" was always missing — the topic
     * had a consumer in github-bot from the start, but nothing ever wrote to it,
     * so no PR outcome was ever captured and the dashboard's merge-rate figures
     * were invented constants.
     */
    @SuppressWarnings("unchecked")
    private ResponseEntity<Void> handlePullRequestOutcome(Map<String, Object> payload) {
        if (!"closed".equals(payload.get("action"))) {
            // opened/synchronize/labeled etc. carry no outcome information.
            return ResponseEntity.accepted().build();
        }

        Map<String, Object> pr = (Map<String, Object>) payload.get("pull_request");
        if (pr == null) return ResponseEntity.accepted().build();

        String jobId = extractJobId((String) pr.get("body"));
        if (jobId == null) {
            logger.info("PR closed but no GitOracle job ID in its body — not one of ours, ignoring.");
            return ResponseEntity.accepted().build();
        }

        // GitHub distinguishes "merged" from merely "closed" via this boolean;
        // action is "closed" in both cases.
        boolean merged = Boolean.TRUE.equals(pr.get("merged"));
        String outcome = merged ? "PR_MERGED" : "PR_CLOSED";

        publishPrEvent(jobId, outcome, senderLogin(payload), (String) pr.get("html_url"));
        logger.info("PR {} for job {} — recorded {}", pr.get("html_url"), jobId, outcome);
        return ResponseEntity.accepted().build();
    }

    @SuppressWarnings("unchecked")
    private ResponseEntity<Void> handlePullRequestReview(Map<String, Object> payload) {
        Map<String, Object> review = (Map<String, Object>) payload.get("review");
        Map<String, Object> pr = (Map<String, Object>) payload.get("pull_request");
        if (review == null || pr == null) return ResponseEntity.accepted().build();

        // GitHub sends review state lowercased ("approved"); be tolerant anyway.
        String state = (String) review.get("state");
        if (state == null || !"approved".equalsIgnoreCase(state)) {
            return ResponseEntity.accepted().build();
        }

        String jobId = extractJobId((String) pr.get("body"));
        if (jobId == null) return ResponseEntity.accepted().build();

        publishPrEvent(jobId, "REVIEW_APPROVED", senderLogin(payload), (String) pr.get("html_url"));
        logger.info("PR {} for job {} — recorded REVIEW_APPROVED", pr.get("html_url"), jobId);
        return ResponseEntity.accepted().build();
    }

    private String extractJobId(String body) {
        if (body == null) return null;
        Matcher m = JOB_ID_PATTERN.matcher(body);
        return m.find() ? m.group(1) : null;
    }

    /**
     * Parses the request body only AFTER HMAC verification — deliberately
     * bound as raw bytes (@RequestBody byte[]) rather than Spring's usual
     * Map<String,Object> auto-binding, since re-serializing an
     * already-parsed Map is not guaranteed to byte-for-byte match what the
     * sender actually signed (key ordering, whitespace, number formatting
     * can all differ), which would make every legitimate signature fail.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> parseBody(byte[] rawBody) {
        try {
            return objectMapper.readValue(rawBody, Map.class);
        } catch (Exception e) {
            logger.warn("Rejected webhook: body is not valid JSON: {}", e.getMessage());
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private String senderLogin(Map<String, Object> payload) {
        Map<String, Object> sender = (Map<String, Object>) payload.get("sender");
        return sender != null ? (String) sender.getOrDefault("login", "unknown") : "unknown";
    }

    /** All-String values: the orchestrator's listener binds this as a
     *  Map<String, String>, matching the other event payloads on that factory. */
    private void publishPrEvent(String jobId, String type, String reviewer, String prUrl) {
        Map<String, Object> event = new HashMap<>();
        event.put("jobId", jobId);
        event.put("type", type);
        event.put("reviewer", reviewer != null ? reviewer : "unknown");
        event.put("prUrl", prUrl != null ? prUrl : "");
        kafkaTemplate.send(KafkaTopics.GITHUB_PR_EVENTS, event);
    }
}
