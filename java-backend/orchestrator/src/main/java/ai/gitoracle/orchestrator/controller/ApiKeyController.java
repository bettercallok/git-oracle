package ai.gitoracle.orchestrator.controller;

import ai.gitoracle.core.model.postgres.ApiKey;
import ai.gitoracle.core.security.ApiKeys;
import ai.gitoracle.orchestrator.dto.Requests;
import ai.gitoracle.orchestrator.repository.ApiKeyRepository;
import ai.gitoracle.orchestrator.security.TenantContext;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Lets a signed-in person create and revoke API keys for their own tenant's
 * scripts, without needing an admin.
 *
 * <p>Only a browser session may use this ({@code X-User-ID}, which the gateway
 * sets only for a session and strips otherwise). If an API key could call it, one
 * leaked key could mint replacements and outlive its own revocation.
 *
 * <p>Keys made here are narrower than {@link ApiKeyAdminController}'s: always the
 * caller's own tenant, never {@code platform:admin}, and they always expire.
 */
// No @CrossOrigin: the API Gateway sets the CORS headers (see DashboardController).
@RestController
@RequestMapping("/api/v1/keys")
public class ApiKeyController {

    private static final Logger logger = LoggerFactory.getLogger(ApiKeyController.class);

    private static final int DEFAULT_EXPIRY_DAYS = 90;
    private static final int MAX_ACTIVE_KEYS = 25;
    private static final Set<String> READ_WRITE = Set.of(ApiKey.Scopes.JOBS_READ, ApiKey.Scopes.JOBS_WRITE);
    private static final Set<String> READ_ONLY = Set.of(ApiKey.Scopes.JOBS_READ);

    private final ApiKeyRepository apiKeys;

    public ApiKeyController(ApiKeyRepository apiKeys) {
        this.apiKeys = apiKeys;
    }

    @GetMapping
    public ResponseEntity<?> list(@RequestHeader(name = "X-User-ID", required = false) UUID userId) {
        if (userId == null) return sessionRequired();

        List<Map<String, Object>> out = new ArrayList<>();
        for (ApiKey key : apiKeys.findByTenantIdOrderByCreatedAtDesc(TenantContext.requireTenantId())) {
            Map<String, Object> row = new LinkedHashMap<>();
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

    /** The full key appears in this response only; only its digest is stored. */
    @PostMapping
    @Transactional
    public ResponseEntity<?> create(@RequestHeader(name = "X-User-ID", required = false) UUID userId,
                                    @Valid @RequestBody Requests.CreateApiKey request) {
        if (userId == null) return sessionRequired();

        UUID tenantId = TenantContext.requireTenantId();
        OffsetDateTime now = OffsetDateTime.now();
        long active = apiKeys.findByTenantIdOrderByCreatedAtDesc(tenantId).stream()
            .filter(key -> key.getRevokedAt() == null)
            .filter(key -> key.getExpiresAt() == null || key.getExpiresAt().isAfter(now))
            .count();
        if (active >= MAX_ACTIVE_KEYS) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                "error", "This tenant already has " + MAX_ACTIVE_KEYS + " active keys. Revoke one first."));
        }

        ApiKeys.GeneratedKey generated = ApiKeys.generate();
        ApiKey key = new ApiKey();
        key.setTenantId(tenantId);
        key.setCreatedByUserId(userId);
        key.setKeyPrefix(generated.prefix());
        key.setKeyHash(generated.hash());
        key.setName(request.name().trim());
        key.setScopes(ApiKey.Scopes.join("read".equals(request.access()) ? READ_ONLY : READ_WRITE));
        key.setCreatedAt(now);
        key.setExpiresAt(now.plusDays(request.expiresInDays() != null ? request.expiresInDays() : DEFAULT_EXPIRY_DAYS));
        apiKeys.save(key);

        logger.info("User {} created API key {} for tenant {}", userId, generated.prefix(), tenantId);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("apiKey", generated.presented());
        body.put("keyPrefix", generated.prefix());
        body.put("name", key.getName());
        body.put("expiresAt", key.getExpiresAt().toString());
        return ResponseEntity.ok(body);
    }

    /** Takes effect at the gateway within its key-cache TTL (default 60s). */
    @DeleteMapping("/{keyPrefix}")
    @Transactional
    public ResponseEntity<?> revoke(@RequestHeader(name = "X-User-ID", required = false) UUID userId,
                                    @PathVariable String keyPrefix) {
        if (userId == null) return sessionRequired();

        ApiKey key = apiKeys.findByKeyPrefix(keyPrefix).orElse(null);
        // Not-found and not-yours look the same, so this can't reveal other tenants' prefixes.
        if (key == null || !TenantContext.requireTenantId().equals(key.getTenantId())) {
            return ResponseEntity.notFound().build();
        }
        if (key.getRevokedAt() == null) {
            key.setRevokedAt(OffsetDateTime.now());
            apiKeys.save(key);
            logger.info("User {} revoked API key {} for tenant {}", userId, keyPrefix, key.getTenantId());
        }
        return ResponseEntity.noContent().build();
    }

    private static ResponseEntity<Map<String, String>> sessionRequired() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
            "error", "Managing API keys requires signing in with GitHub; an API key can't do it."));
    }
}
