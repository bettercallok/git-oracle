package ai.gitoracle.core.model.postgres;

import jakarta.persistence.*;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;

/**
 * One issued API key, owned by exactly one tenant.
 *
 * This entity is what makes tenancy real. Before it, {@code X-Tenant-ID} was a
 * plain request header the client chose for itself and nothing validated it
 * against the credential presented alongside it — so any caller holding the
 * single shared key could read and write any tenant's data simply by changing a
 * header value. A tenant is now a property <em>of the key</em>, derived
 * server-side, and no request can name a tenant it does not hold a key for.
 *
 * <p>The full key is never stored. {@link #keyHash} is a SHA-256 digest of the
 * presented value (see {@link ai.gitoracle.core.security.ApiKeys} for why plain
 * SHA-256 is the correct choice for a high-entropy random secret) and
 * {@link #keyPrefix} is the public, indexed half used to locate the row before
 * that digest is compared in constant time. A database dump therefore yields no
 * usable credentials.
 */
@Entity
@Table(name = "api_keys", indexes = {
    @Index(name = "idx_api_keys_prefix", columnList = "key_prefix", unique = true),
    @Index(name = "idx_api_keys_tenant", columnList = "tenant_id")
})
@Data
public class ApiKey {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    /** Public lookup half of the key. Unique so a prefix collision is a hard failure, not a silent mis-auth. */
    @Column(name = "key_prefix", nullable = false, unique = true, length = 32)
    private String keyPrefix;

    @Column(name = "key_hash", nullable = false, length = 128)
    private String keyHash;

    /** Human label so an operator can tell two keys apart when revoking one. */
    @Column(name = "name")
    private String name;

    /**
     * Comma-separated scope list. Stored as text rather than a join table
     * because the set is small, fixed, and read on every single request — the
     * gateway resolves a key with one indexed row read and no joins.
     */
    @Column(name = "scopes", columnDefinition = "TEXT")
    private String scopes = "";

    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    /**
     * Best-effort last-use timestamp, for spotting keys that can safely be
     * revoked. Deliberately not written on every request — that would turn every
     * read-only API call into a database write and make the key row a
     * write-contention hotspot. The gateway updates it at most once per cache
     * refresh interval per key.
     */
    @Column(name = "last_used_at")
    private OffsetDateTime lastUsedAt;

    /** Non-null means revoked; the row is kept for audit rather than deleted. */
    @Column(name = "revoked_at")
    private OffsetDateTime revokedAt;

    /** Null means no expiry. */
    @Column(name = "expires_at")
    private OffsetDateTime expiresAt;

    public Set<String> scopeSet() {
        return Scopes.parse(scopes);
    }

    /**
     * The scopes a key can carry. Kept as a tiny nested type rather than an enum
     * so that an unrecognised scope string read from an older row is simply
     * ignored rather than crashing deserialization of the whole key.
     */
    public static final class Scopes {

        /** Full platform administration: mint/revoke keys, create tenants. */
        public static final String PLATFORM_ADMIN = "platform:admin";

        /** Ordinary read/write against the holder's own tenant. */
        public static final String JOBS_WRITE = "jobs:write";
        public static final String JOBS_READ = "jobs:read";

        private Scopes() {}

        public static Set<String> parse(String raw) {
            if (raw == null || raw.isBlank()) return Set.of();
            java.util.Set<String> out = new java.util.LinkedHashSet<>();
            for (String s : raw.split(",")) {
                String trimmed = s.trim();
                if (!trimmed.isEmpty()) out.add(trimmed);
            }
            return java.util.Collections.unmodifiableSet(out);
        }

        public static String join(java.util.Collection<String> scopes) {
            return String.join(",", scopes);
        }
    }
}
