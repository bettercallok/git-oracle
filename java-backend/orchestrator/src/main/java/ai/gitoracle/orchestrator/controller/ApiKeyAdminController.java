package ai.gitoracle.orchestrator.controller;

import ai.gitoracle.core.model.postgres.ApiKey;
import ai.gitoracle.core.model.postgres.Tenant;
import ai.gitoracle.core.security.ApiKeys;
import ai.gitoracle.orchestrator.repository.ApiKeyRepository;
import ai.gitoracle.orchestrator.security.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import jakarta.persistence.EntityManager;
import java.time.OffsetDateTime;
import java.util.*;

/**
 * Issue, list, and revoke API keys.
 *
 * <p>Mounted under {@code /api/v1/admin/**}, which the api-gateway gates on the
 * {@code platform:admin} scope. That check is enforced at the gateway rather
 * than duplicated here for the same reason the internal-token check is: this
 * service is not externally reachable, and every request that arrives has
 * already been through it. The scope is nonetheless re-read from
 * {@link TenantContext} for the one decision the gateway cannot make — which
 * tenant a key may be minted <em>for</em>.
 */
// No @CrossOrigin: reached only through the API Gateway, which already sets
// Access-Control-Allow-Origin globally — a duplicate here breaks CORS entirely
// (see DashboardController for the full explanation).
@RestController
@RequestMapping("/api/v1/admin/api-keys")
public class ApiKeyAdminController {

    private static final Logger logger = LoggerFactory.getLogger(ApiKeyAdminController.class);

    private final ApiKeyRepository apiKeyRepository;
    private final EntityManager entityManager;

    public ApiKeyAdminController(ApiKeyRepository apiKeyRepository, EntityManager entityManager) {
        this.apiKeyRepository = apiKeyRepository;
        this.entityManager = entityManager;
    }

    /**
     * Mints a key. The full key is returned <b>once</b> and is not recoverable
     * afterwards — only its SHA-256 digest is stored.
     *
     * <p>{@code tenantId} is optional and defaults to the caller's own tenant.
     * Naming a different tenant requires {@code platform:admin}, so an ordinary
     * tenant-scoped admin key cannot mint itself credentials for somebody else's
     * data.
     */
    @PostMapping
    @Transactional
    public ResponseEntity<Map<String, Object>> mint(@RequestBody Map<String, Object> request) {
        UUID callerTenant = TenantContext.requireTenantId();

        UUID targetTenant = callerTenant;
        Object requested = request.get("tenantId");
        if (requested instanceof String s && !s.isBlank()) {
            try {
                targetTenant = UUID.fromString(s.trim());
            } catch (IllegalArgumentException e) {
                return ResponseEntity.badRequest().body(Map.of("error", "tenantId is not a valid UUID"));
            }
            if (!targetTenant.equals(callerTenant) && !TenantContext.isPlatformAdmin()) {
                return ResponseEntity.status(403).body(Map.of(
                    "error", "Minting a key for another tenant requires the platform:admin scope."));
            }
        }

        if (entityManager.find(Tenant.class, targetTenant) == null) {
            // Refuse rather than create. A key pointing at a tenant row that
            // does not exist would authenticate successfully and then scope
            // every query to a tenant with no data — a confusing failure that
            // looks like data loss.
            return ResponseEntity.badRequest().body(Map.of("error", "No such tenant: " + targetTenant));
        }

        Set<String> scopes = parseScopes(request.get("scopes"));
        if (scopes.contains(ApiKey.Scopes.PLATFORM_ADMIN) && !TenantContext.isPlatformAdmin()) {
            return ResponseEntity.status(403).body(Map.of(
                "error", "Cannot grant platform:admin from a key that does not hold it."));
        }

        ApiKeys.GeneratedKey generated = ApiKeys.generate();

        ApiKey key = new ApiKey();
        key.setTenantId(targetTenant);
        key.setKeyPrefix(generated.prefix());
        key.setKeyHash(generated.hash());
        key.setName(request.get("name") instanceof String n && !n.isBlank() ? n : "unnamed");
        key.setScopes(ApiKey.Scopes.join(scopes));
        key.setCreatedAt(OffsetDateTime.now());
        key.setExpiresAt(parseExpiry(request.get("expiresInDays")));
        apiKeyRepository.save(key);

        logger.info("Minted API key {} for tenant {} with scopes {} (requested by tenant {})",
            generated.prefix(), targetTenant, key.getScopes(), callerTenant);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("apiKey", generated.presented());
        body.put("keyPrefix", generated.prefix());
        body.put("tenantId", targetTenant.toString());
        body.put("scopes", scopes);
        body.put("expiresAt", key.getExpiresAt() == null ? null : key.getExpiresAt().toString());
        body.put("warning", "This is the only time the full key is shown. Store it now — it cannot be recovered.");
        return ResponseEntity.ok(body);
    }

    /** Lists the caller's own tenant's keys. Never returns a hash or a full key. */
    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> list() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ApiKey key : apiKeyRepository.findByTenantIdOrderByCreatedAtDesc(TenantContext.requireTenantId())) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", key.getId().toString());
            row.put("keyPrefix", key.getKeyPrefix());
            row.put("name", key.getName());
            row.put("scopes", key.scopeSet());
            row.put("createdAt", key.getCreatedAt());
            row.put("lastUsedAt", key.getLastUsedAt());
            row.put("expiresAt", key.getExpiresAt());
            row.put("revokedAt", key.getRevokedAt());
            out.add(row);
        }
        return ResponseEntity.ok(out);
    }

    /**
     * Revokes a key. The row is kept, timestamped, rather than deleted, so
     * "which key did this" remains answerable after the fact.
     *
     * <p>Revocation takes effect at the gateway within its key-cache TTL
     * (default 60s), not instantly — see {@code ApiKeyAuthenticator}.
     */
    @DeleteMapping("/{keyPrefix}")
    @Transactional
    public ResponseEntity<Map<String, Object>> revoke(@PathVariable String keyPrefix) {
        ApiKey key = apiKeyRepository.findByKeyPrefix(keyPrefix).orElse(null);

        // Not-found and not-yours are the same response, so a caller cannot use
        // this endpoint to discover which key prefixes exist in other tenants.
        if (key == null || !TenantContext.requireTenantId().equals(key.getTenantId())) {
            return ResponseEntity.notFound().build();
        }
        if (key.getRevokedAt() != null) {
            return ResponseEntity.ok(Map.of("keyPrefix", keyPrefix, "status", "ALREADY_REVOKED"));
        }

        key.setRevokedAt(OffsetDateTime.now());
        apiKeyRepository.save(key);
        logger.info("Revoked API key {} for tenant {}", keyPrefix, key.getTenantId());

        return ResponseEntity.ok(Map.of(
            "keyPrefix", keyPrefix,
            "status", "REVOKED",
            "note", "Takes effect at the gateway within its key-cache TTL (default 60s)."));
    }

    private static Set<String> parseScopes(Object raw) {
        if (raw instanceof String s) return ApiKey.Scopes.parse(s);
        if (raw instanceof Collection<?> c) {
            Set<String> out = new LinkedHashSet<>();
            for (Object o : c) {
                if (o instanceof String s && !s.isBlank()) out.add(s.trim());
            }
            if (!out.isEmpty()) return out;
        }
        // Least privilege by default: ordinary pipeline access, no administration.
        return Set.of(ApiKey.Scopes.JOBS_READ, ApiKey.Scopes.JOBS_WRITE);
    }

    private static OffsetDateTime parseExpiry(Object raw) {
        if (raw instanceof Number n && n.intValue() > 0) {
            return OffsetDateTime.now().plusDays(n.intValue());
        }
        return null;
    }
}
