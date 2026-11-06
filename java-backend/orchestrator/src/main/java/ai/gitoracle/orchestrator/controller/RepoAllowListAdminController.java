package ai.gitoracle.orchestrator.controller;

import ai.gitoracle.core.model.postgres.Tenant;
import ai.gitoracle.orchestrator.security.TenantContext;
import ai.gitoracle.orchestrator.service.RepoAccessService;
import jakarta.persistence.EntityManager;
import ai.gitoracle.orchestrator.service.AuditLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * A tenant's repository allow-list, and its Sentry webhook secret.
 *
 * <p>Under {@code /api/v1/admin/**}, so the gateway and {@code TenantContextFilter}
 * already require {@code platform:admin}. Each endpoint works on the caller's
 * own tenant unless {@code tenantId} names another.
 */
// No @CrossOrigin: reached only through the API Gateway (see DashboardController).
@RestController
@RequestMapping("/api/v1/admin")
public class RepoAllowListAdminController {

    private static final Logger logger = LoggerFactory.getLogger(RepoAllowListAdminController.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final RepoAccessService repoAccess;
    private final EntityManager entityManager;
    private final AuditLog audit;

    public RepoAllowListAdminController(RepoAccessService repoAccess, EntityManager entityManager, AuditLog audit) {
        this.repoAccess = repoAccess;
        this.entityManager = entityManager;
        this.audit = audit;
    }

    @GetMapping("/repos")
    public ResponseEntity<Map<String, Object>> list(@RequestParam(required = false) String tenantId) {
        UUID tenant = tenant(tenantId);
        return ResponseEntity.ok(Map.of("tenantId", tenant.toString(), "repos", repoAccess.list(tenant)));
    }

    /** Body: {@code {"repo": "owner/name" or a GitHub URL, "tenantId": optional}}. Idempotent. */
    @PostMapping("/repos")
    public ResponseEntity<Map<String, Object>> add(@RequestBody Map<String, String> body) {
        UUID tenant = tenant(body.get("tenantId"));
        if (entityManager.find(Tenant.class, tenant) == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "No such tenant: " + tenant));
        }
        try {
            String slug = repoAccess.add(tenant, body.get("repo"));
            audit.record(tenant, "repo.allowed", slug, null);
            logger.info("Allow-list: tenant {} may now use {}", tenant, slug);
            return ResponseEntity.ok(Map.of("tenantId", tenant.toString(), "repo", slug));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @DeleteMapping("/repos")
    public ResponseEntity<Map<String, Object>> remove(@RequestParam String repo,
                                                      @RequestParam(required = false) String tenantId) {
        UUID tenant = tenant(tenantId);
        if (!repoAccess.remove(tenant, repo)) {
            return ResponseEntity.notFound().build();
        }
        audit.record(tenant, "repo.removed", repo, null);
        logger.info("Allow-list: tenant {} may no longer use {}", tenant, repo);
        return ResponseEntity.ok(Map.of("tenantId", tenant.toString(), "removed", repo));
    }

    /**
     * Generates (or rotates) the tenant's Sentry webhook secret and returns it
     * once. Configure it as the client secret of that tenant's Sentry
     * integration; the old secret stops working immediately.
     */
    @PostMapping("/tenants/{id}/sentry-secret")
    @Transactional
    public ResponseEntity<Map<String, Object>> rotateSentrySecret(@PathVariable UUID id) {
        Tenant tenant = entityManager.find(Tenant.class, id);
        if (tenant == null) {
            return ResponseEntity.notFound().build();
        }
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String secret = HexFormat.of().formatHex(bytes);
        tenant.setSentryWebhookSecret(secret);
        logger.info("Rotated the Sentry webhook secret for tenant {}", id);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tenantId", id.toString());
        out.put("sentryWebhookSecret", secret);
        out.put("webhookUrl", "/webhook/" + id + "/sentry");
        out.put("warning", "This is the only time the secret is shown.");
        return ResponseEntity.ok(out);
    }

    private static UUID tenant(String requested) {
        if (requested == null || requested.isBlank()) {
            return TenantContext.requireTenantId();
        }
        try {
            return UUID.fromString(requested.trim());
        } catch (IllegalArgumentException e) {
            throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.BAD_REQUEST, "tenantId is not a valid UUID");
        }
    }
}
