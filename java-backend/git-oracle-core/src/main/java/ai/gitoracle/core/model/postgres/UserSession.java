package ai.gitoracle.core.model.postgres;

import jakarta.persistence.*;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One signed-in browser. The cookie's token is never stored, only its digest
 * ({@link ai.gitoracle.core.security.SessionTokens}), so a database dump yields
 * no usable sessions. The orchestrator writes these rows; the api-gateway reads
 * them over plain JDBC on the request path, as it does for {@link ApiKey}.
 */
@Entity
@Table(name = "user_sessions", indexes = {
    @Index(name = "idx_user_sessions_token_hash", columnList = "token_hash", unique = true),
    @Index(name = "idx_user_sessions_user", columnList = "user_id")
})
@Data
public class UserSession {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "token_hash", nullable = false, unique = true, length = 128)
    private String tokenHash;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    /** Same format as {@link ApiKey#getScopes()}; fixed when the session is created. */
    @Column(name = "scopes", columnDefinition = "TEXT")
    private String scopes = "";

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    /** Non-null means signed out or revoked. */
    @Column(name = "revoked_at")
    private OffsetDateTime revokedAt;
}
