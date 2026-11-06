package ai.gitoracle.gateway.security;

import ai.gitoracle.core.model.postgres.ApiKey;
import ai.gitoracle.core.security.SessionTokens;
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
 * Resolves a browser's session cookie into the user and tenant it belongs to.
 *
 * <p>Built like {@link ApiKeyAuthenticator}: blocking JDBC off the event loop, a
 * short cache because the dashboard polls every few seconds, and fail-closed if
 * the database can't answer. The cache is much shorter than the API key one, because
 * removing someone from an organisation should take effect within seconds. {@link
 * #evict} makes signing out and switching organisation immediate.
 */
@Component
public class SessionAuthenticator {

    private static final Logger logger = LoggerFactory.getLogger(SessionAuthenticator.class);

    public record SessionPrincipal(UUID sessionId, UUID userId, UUID tenantId, Set<String> scopes) {}

    private record CachedSession(
        SessionPrincipal principal,
        OffsetDateTime revokedAt,
        OffsetDateTime expiresAt,
        Instant cachedAt
    ) {}

    private static final String LOOKUP_SQL = """
        SELECT id, user_id, tenant_id, scopes, revoked_at, expires_at
        FROM user_sessions
        WHERE token_hash = ?
        """;

    private final JdbcTemplate jdbcTemplate;
    private final ConcurrentHashMap<String, CachedSession> cache = new ConcurrentHashMap<>();

    @Value("${gitoracle.session-cache-ttl-seconds:5}")
    private long cacheTtlSeconds;

    public SessionAuthenticator(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** @return the principal, or empty if the token is malformed, unknown, revoked or expired */
    public Mono<Optional<SessionPrincipal>> authenticate(String token) {
        if (!SessionTokens.isWellFormed(token)) {
            return Mono.just(Optional.empty());
        }
        String hash = SessionTokens.hash(token);

        CachedSession cached = cache.get(hash);
        if (cached != null && !isStale(cached)) {
            return Mono.just(verify(cached));
        }

        return Mono.fromCallable(() -> {
                CachedSession fresh = load(hash);
                cache.put(hash, fresh);
                pruneCache();
                return verify(fresh);
            })
            .subscribeOn(Schedulers.boundedElastic())
            .onErrorResume(e -> {
                logger.error("Session lookup failed — rejecting the request rather than allowing it unverified: {}", e.toString());
                return Mono.just(Optional.empty());
            });
    }

    /** Drops the cached decision so a signed-out cookie stops working straight away. */
    public void evict(String token) {
        if (SessionTokens.isWellFormed(token)) {
            cache.remove(SessionTokens.hash(token));
        }
    }

    private Optional<SessionPrincipal> verify(CachedSession session) {
        if (session.principal() == null) return Optional.empty();
        OffsetDateTime now = OffsetDateTime.now();
        if (session.revokedAt() != null && !session.revokedAt().isAfter(now)) return Optional.empty();
        if (session.expiresAt() == null || !session.expiresAt().isAfter(now)) return Optional.empty();
        return Optional.of(session.principal());
    }

    private CachedSession load(String hash) {
        try {
            Map<String, Object> row = jdbcTemplate.queryForMap(LOOKUP_SQL, hash);
            SessionPrincipal principal = new SessionPrincipal(
                (UUID) row.get("id"),
                (UUID) row.get("user_id"),
                (UUID) row.get("tenant_id"),
                ApiKey.Scopes.parse((String) row.get("scopes")));
            return new CachedSession(principal, toOffsetDateTime(row.get("revoked_at")),
                toOffsetDateTime(row.get("expires_at")), Instant.now());
        } catch (EmptyResultDataAccessException e) {
            return new CachedSession(null, null, null, Instant.now());
        }
    }

    private static OffsetDateTime toOffsetDateTime(Object value) {
        if (value == null) return null;
        if (value instanceof OffsetDateTime odt) return odt;
        if (value instanceof java.sql.Timestamp ts) return ts.toInstant().atOffset(OffsetDateTime.now().getOffset());
        if (value instanceof Instant i) return i.atOffset(OffsetDateTime.now().getOffset());
        return null;
    }

    private boolean isStale(CachedSession cached) {
        if (cacheTtlSeconds <= 0) return true;
        return cached.cachedAt().plus(Duration.ofSeconds(cacheTtlSeconds)).isBefore(Instant.now());
    }

    private void pruneCache() {
        if (cache.size() < 10_000) return;
        cache.entrySet().removeIf(e -> isStale(e.getValue()));
        if (cache.size() >= 10_000) cache.clear();
    }
}
