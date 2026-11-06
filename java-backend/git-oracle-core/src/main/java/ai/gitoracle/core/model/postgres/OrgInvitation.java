package ai.gitoracle.core.model.postgres;

import jakarta.persistence.*;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * An invitation to join an organisation, accepted automatically at sign-in.
 *
 * <p>It's tied to the GitHub account's numeric id, looked up when the invitation is
 * made, not to the login: a login can be released and claimed by someone else
 * before the invited person signs in.
 */
@Entity
@Table(name = "organization_invitations", indexes = {
    @Index(name = "idx_org_invitations_github", columnList = "github_id"),
    @Index(name = "idx_org_invitations_tenant", columnList = "tenant_id")
})
@Data
public class OrgInvitation {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "github_id", nullable = false)
    private Long githubId;

    @Column(name = "github_login", nullable = false)
    private String githubLogin;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 16)
    private OrgRole role;

    /** Null when it was made with an API key rather than by a signed-in person. */
    @Column(name = "invited_by_user_id")
    private UUID invitedByUserId;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    @Column(name = "accepted_at")
    private OffsetDateTime acceptedAt;

    @Column(name = "revoked_at")
    private OffsetDateTime revokedAt;
}
