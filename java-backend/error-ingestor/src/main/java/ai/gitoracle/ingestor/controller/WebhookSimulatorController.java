package ai.gitoracle.ingestor.controller;

import ai.gitoracle.core.model.postgres.ApiKey;
import ai.gitoracle.core.security.RepoNames;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Lets an admin simulate a failed GitHub Actions run from the dashboard.
 *
 * <p>A real webhook is authenticated by its signature, but a browser can't sign
 * without holding the secret, so this authenticates the admin instead. The scope
 * is checked here as well as at the gateway, because anything holding the internal
 * token can reach this service directly.
 *
 * <p>It accepts only a failed {@code workflow_run}. That can do no more than an
 * admin's {@code /trigger} can, whereas review comments and PR outcomes must keep
 * coming from GitHub: a forged one could rewrite a PR or skew the merge statistics.
 * The orchestrator still checks the repository against the tenant's allow-list.
 */
// No @CrossOrigin: the API Gateway sets the CORS headers.
@RestController
@RequestMapping("/api/v1/admin/webhook-simulator")
public class WebhookSimulatorController {

    private static final Logger logger = LoggerFactory.getLogger(WebhookSimulatorController.class);

    private final WebhookController webhooks;

    public WebhookSimulatorController(WebhookController webhooks) {
        this.webhooks = webhooks;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> simulate(
            @RequestHeader(name = "X-Tenant-ID", required = false) UUID tenantId,
            @RequestHeader(name = "X-Scopes", required = false) String scopes,
            @RequestBody Map<String, Object> payload) {

        if (tenantId == null || !ApiKey.Scopes.parse(scopes).contains(ApiKey.Scopes.PLATFORM_ADMIN)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                "error", "The webhook simulator requires the platform:admin scope."));
        }

        if (!(payload.get("workflow_run") instanceof Map<?, ?> run)
                || !(payload.get("repository") instanceof Map<?, ?> repository)
                || !"completed".equals(payload.get("action"))
                || !"failure".equals(run.get("conclusion"))) {
            return badRequest("Only a failed run can start a job: action must be \"completed\" and "
                + "workflow_run.conclusion must be \"failure\".");
        }
        if (!(run.get("id") instanceof Number id)) {
            return badRequest("workflow_run.id must be a number.");
        }
        if (!(repository.get("full_name") instanceof String repo) || RepoNames.slug(repo) == null) {
            return badRequest("repository.full_name must be owner/name.");
        }

        ResponseEntity<Void> result = webhooks.handleWorkflowRun(tenantId, payload);
        if (!result.getStatusCode().is2xxSuccessful()) {
            return badRequest("The payload could not be processed.");
        }

        logger.info("Admin simulated a failed workflow_run for {} (run {}, tenant {})", repo, id, tenantId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", "Accepted. A job starts unless this run id was already used.");
        body.put("repo", repo);
        body.put("runId", id.longValue());
        return ResponseEntity.accepted().body(body);
    }

    private static ResponseEntity<Map<String, Object>> badRequest(String message) {
        return ResponseEntity.badRequest().body(Map.of("error", message));
    }
}
