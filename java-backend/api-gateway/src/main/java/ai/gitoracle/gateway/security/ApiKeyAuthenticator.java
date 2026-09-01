package ai.gitoracle.gateway.security;

import ai.gitoracle.core.model.postgres.ApiKey;
import ai.gitoracle.core.security.ApiKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves a presented {@code X-API-Key} into the tenant that owns it.
 *
 * <p>This is the single place the gateway decides <em>who</em> a request is, and
 * it is the reason {@code X-Tenant-ID} can be trusted downstream at all. Before
 * this class existed, tenancy was whatever the caller put in a header.
 *
 * <h2>Blocking JDBC in a reactive gateway</h2>
 * The api-gateway is WebFlux, so a blocking database call on the event loop
 * would stall every other in-flight request on that thread. The lookup is
 * therefore dispatched to {@link Schedulers#boundedElastic()}. It is also
 * cached, so the overwhelming majority of requests never touch the database at
 * all — without the cache this would add a synchronous round-trip to the
 * critical path of every single API call.
 *
 * <p>R2DBC would avoid the thread hop entirely and is the better long-term
 * answer; it is not worth pulling a second, differently-shaped database stack
 * into the build for one indexed single-row read that is cached anyway.
 *
 * <h2>What the cache costs</h2>
 * A revoked or expired key stays usable for up to {@code cache-ttl} (default 60
 * seconds) after revocation. That is a deliberate, bounded trade and it is the
 * one behaviour here an operator must know about: revocation is not
 * instantaneous. For an urgent compromise, restart the gateway — that clears the
 * cache outright. Setting the TTL to zero disables caching and makes revocation
 * immediate at the cost of a database read per request.
 */
@Component
public class ApiKeyAuthenticator {

    private static final Logger logger = LoggerFactory.getLogger(ApiKeyAuthenticator.class);

    /** Resolved caller identity. Never constructed from anything the client sent. */
    public record Principal(UUID tenantId, Set<String> scopes, String keyPrefix, boolean legacy) {
        public boolean hasScope(String scope) {
            return scopes.contains(scope);
        }
    }

    private record CachedKey(
        String keyHash,
        UUID tenantId,
        String scopes,
        OffsetDateTime revokedAt,
        OffsetDateTime expiresAt,
        Instant cachedAt,
        boolean found
    ) {}

    private static final String LOOKUP_SQL = """
        SELECT key_hash, tenant_id, scopes, revoked_at, expires_at
        FROM api_keys
        WHERE key_prefix = ?
        """;

    private final JdbcTemplate jdbcTemplate;
    private final ConcurrentHashMap<String, CachedKey> cache = new ConcurrentHashMap<>();

    @Value("${gitoracle.api-key-cache-ttl-seconds:60}")
    private long cacheTtlSeconds;

    /**
     * The pre-multi-tenancy single shared secret. Still accepted so that the
     * dashboard, CLI, and eval harness keep working through the migration —
     * see {@link #resolveLegacy} for the constraints placed on it.
     */
    @Value("${gitoracle.api-key:}")
    private String legacyApiKey;

    @Value("${gitoracle.legacy-api-key-enabled:true}")
    private boolean legacyApiKeyEnabled;

    @Value("${gitoracle.default-tenant-id:00000000-0000-0000-0000-000000000000}")
    private String defaultTenantId;

    public ApiKeyAuthenticator(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * @return the resolved principal, or empty if the key is absent, malformed,
     *         unknown, revoked, or expired. The caller must not distinguish
     *         between those cases in its response — telling an attacker that a
     *         key exists but is revoked is information they did not have.
     */
    public Mono<Optional<Principal>> authenticate(String presentedKey) {
        if (presentedKey == null || presentedKey.isBlank()) {
            return Mono.just(Optional.empty());
        }

        // A key that isn't shaped like a GitOracle key can only be the legacy
        // shared secret. Checking the shape first means a legacy comparison is
        // never performed against a well-formed key, and vice versa.
        if (!ApiKeys.looksLikeApiKey(presentedKey)) {
            return Mono.just(resolveLegacy(presentedKey));
        }

        String prefix = ApiKeys.extractPrefix(presentedKey);
        CachedKey cached = cache.get(prefix);
        if (cached != null && !isStale(cached)) {
            return Mono.just(verify(presentedKey, cached));
        }

        return Mono.fromCallable(() -> {
                CachedKey fresh = loadFromDatabase(prefix);
                cache.put(prefix, fresh);
                pruneCache();
                return verify(presentedKey, fresh);
            })
            .subscribeOn(Schedulers.boundedElastic())
            .onErrorResume(e -> {
                // Fail closed. A database outage must not become an
                // authentication bypass, and it must not become an
                // authentication *grant* for a key we could not check.
                logger.error("API key lookup failed for prefix={} — rejecting request rather than allowing it unverified: {}",
                    prefix, e.toString());
                return Mono.just(Optional.empty());
            });
    }

    /**
     * The legacy single shared key, retained only so the existing dashboard,
     * CLI, and eval harness are not broken by this change.
     *
     * <p>It resolves to the default tenant and carries {@code platform:admin} —
     * which is exactly why it must not survive to production: a single secret
     * that grants administration of every tenant is the H1 finding restated. It
     * exists to bootstrap the first real key, and {@code
     * gitoracle.legacy-api-key-enabled} must be set to {@code false} once that
     * key has been issued. The startup warning in
     * {@link ai.gitoracle.gateway.ApiGatewayApplication} says so out loud on
     * every boot while it is on.
     */
    private Optional<Principal> resolveLegacy(String presentedKey) {
        if (!legacyApiKeyEnabled) return Optional.empty();
        if (legacyApiKey == null || legacyApiKey.isBlank()) return Optional.empty();
        if (!ApiKeys.constantTimeEquals(presentedKey, legacyApiKey)) return Optional.empty();

        return Optional.of(new Principal(
            UUID.fromString(defaultTenantId),
            Set.of(ApiKey.Scopes.PLATFORM_ADMIN, ApiKey.Scopes.JOBS_READ, ApiKey.Scopes.JOBS_WRITE),
            "legacy",
            true));
    }

    private Optional<Principal> verify(String presentedKey, CachedKey row) {
        if (!row.found()) return Optional.empty();

        // Constant-time even though we already know the row exists: the hash
        // comparison is the actual authentication step (H10).
        if (!ApiKeys.matches(presentedKey, row.keyHash())) return Optional.empty();

        OffsetDateTime now = OffsetDateTime.now();
        if (row.revokedAt() != null && !row.revokedAt().isAfter(now)) return Optional.empty();
        if (row.expiresAt() != null && !row.expiresAt().isAfter(now)) return Optional.empty();

        return Optional.of(new Principal(
            row.tenantId(),
            ApiKey.Scopes.parse(row.scopes()),
            ApiKeys.extractPrefix(presentedKey),
            false));
    }

    private CachedKey loadFromDatabase(String prefix) {
        try {
            Map<String, Object> row = jdbcTemplate.queryForMap(LOOKUP_SQL, prefix);
            touchLastUsed(prefix);
            return new CachedKey(
                (String) row.get("key_hash"),
                (UUID) row.get("tenant_id"),
                (String) row.get("scopes"),
                toOffsetDateTime(row.get("revoked_at")),
                toOffsetDateTime(row.get("expires_at")),
                Instant.now(),
                true);
        } catch (EmptyResultDataAccessException e) {
            // Cache the miss too. Otherwise a caller spraying random key-shaped
            // strings turns into one database read per attempt.
            return new CachedKey(null, null, null, null, null, Instant.now(), false);
        }
    }

    /**
     * Written only on a cache refresh, so at most once per TTL per key rather
     * than once per request — see {@link ApiKey#getLastUsedAt()}. A failure here
     * is irrelevant to authentication and must never fail the request.
     */
    private void touchLastUsed(String prefix) {
        try {
            jdbcTemplate.update("UPDATE api_keys SET last_used_at = now() WHERE key_prefix = ?", prefix);
        } catch (Exception e) {
            logger.debug("Could not update last_used_at for key prefix={}: {}", prefix, e.toString());
        }
    }

    private static OffsetDateTime toOffsetDateTime(Object value) {
        if (value == null) return null;
        if (value instanceof OffsetDateTime odt) return odt;
        if (value instanceof java.sql.Timestamp ts) return ts.toInstant().atOffset(OffsetDateTime.now().getOffset());
        if (value instanceof java.time.Instant i) return i.atOffset(OffsetDateTime.now().getOffset());
        return null;
    }

    private boolean isStale(CachedKey cached) {
        if (cacheTtlSeconds <= 0) return true;
        return cached.cachedAt().plus(Duration.ofSeconds(cacheTtlSeconds)).isBefore(Instant.now());
    }

    /**
     * Bounded so that key-shaped garbage from an unauthenticated caller cannot
     * grow the map without limit — every miss would otherwise add an entry.
     */
    private void pruneCache() {
        if (cache.size() < 10_000) return;
        cache.entrySet().removeIf(e -> isStale(e.getValue()));
        if (cache.size() >= 10_000) cache.clear();
    }

    /** Test seam and operator escape hatch: drops every cached decision immediately. */
    public void invalidateAll() {
        cache.clear();
    }
}
