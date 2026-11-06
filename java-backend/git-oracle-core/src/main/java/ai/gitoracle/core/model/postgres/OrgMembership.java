package ai.gitoracle.core.model.postgres;

import jakarta.persistence.*;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.UUID;

/** One person's role in one organisation (a {@link Tenant}). A person can belong to several. */
@Entity
@Table(name = "organization_members", uniqueConstraints =
    @UniqueConstraint(name = "uq_org_member", columnNames = {"tenant_id", "user_id"}),
    indexes = @Index(name = "idx_org_members_user", columnList = "user_id"))
@Data
public class OrgMembership {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 16)
    private OrgRole role;

    /**
     * Who invited this person. Null means the membership came from the operator's
     * settings (the admin and invite lists) rather than an invitation, so it only
     * lasts while those settings still allow it. See LoginService.
     */
    @Column(name = "invited_by_user_id")
    private UUID invitedByUserId;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;
}
