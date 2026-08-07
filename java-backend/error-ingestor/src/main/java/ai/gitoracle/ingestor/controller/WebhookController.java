package ai.gitoracle.ingestor.controller;

import ai.gitoracle.core.kafka.KafkaTopics;
import ai.gitoracle.ingestor.service.SemanticDedupService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/webhook")
public class WebhookController {

    private static final Logger logger = LoggerFactory.getLogger(WebhookController.class);
    private static final Pattern JOB_ID_PATTERN =
        Pattern.compile("<!--\\s*gitoracle:job_id:([0-9a-fA-F\\-]{36})\\s*-->");

    private final SemanticDedupService dedupService;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    public WebhookController(SemanticDedupService dedupService, KafkaTemplate<String, Object> kafkaTemplate) {
        this.dedupService = dedupService;
        this.kafkaTemplate = kafkaTemplate;
    }

    @PostMapping("/{tenantId}/sentry")
    public ResponseEntity<Void> sentryWebhook(
            @PathVariable UUID tenantId,
            @RequestBody Map<String, Object> payload,
            @RequestHeader(value = "X-Sentry-Signature", required = false) String sig) {
        
        logger.info("Received Sentry webhook for tenant: {}", tenantId);
        dedupService.ingest(tenantId, payload);
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/github")
    public ResponseEntity<Void> githubWebhook(
            @RequestHeader(value = "X-Tenant-ID", defaultValue = "00000000-0000-0000-0000-000000000000") UUID tenantId,
            @RequestHeader(value = "X-GitHub-Event", required = false) String githubEvent,
            @RequestBody Map<String, Object> payload) {
        
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
            String action = (String) payload.get("action");
            @SuppressWarnings("unchecked")
            Map<String, Object> workflowRun = (Map<String, Object>) payload.get("workflow_run");
            @SuppressWarnings("unchecked")
            Map<String, Object> repository = (Map<String, Object>) payload.get("repository");

            if ("completed".equals(action) && workflowRun != null && repository != null) {
                String conclusion = (String) workflowRun.get("conclusion");
                if ("failure".equals(conclusion)) {
                    String repoName = (String) repository.get("full_name");
                    Long runIdLong = ((Number) workflowRun.get("id")).longValue();
                    String runId = String.valueOf(runIdLong);
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

        // Fallback: custom/mocked payload
        dedupService.ingest(tenantId, payload);
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

        // Extract PR body (contains the embedded job_id marker)
        String jobId = null;

        // Try pull_request block first (review comment)
        Map<String, Object> pr = (Map<String, Object>) payload.get("pull_request");
        if (pr != null) {
            String prBody = (String) pr.get("body");
            if (prBody != null) {
                Matcher m = JOB_ID_PATTERN.matcher(prBody);
                if (m.find()) jobId = m.group(1);
            }
        }
        // Fallback: try issue block (issue_comment on PR)
        if (jobId == null) {
            Map<String, Object> issue = (Map<String, Object>) payload.get("issue");
            if (issue != null) {
                String issueBody = (String) issue.get("body");
                if (issueBody != null) {
                    Matcher m = JOB_ID_PATTERN.matcher(issueBody);
                    if (m.find()) jobId = m.group(1);
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

            kafkaTemplate.send("job.events.review.received", reviewEvent);
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
