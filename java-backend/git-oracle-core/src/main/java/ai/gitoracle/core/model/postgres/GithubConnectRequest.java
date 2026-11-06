package ai.gitoracle.core.model.postgres;

import jakarta.persistence.*;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * An owner connecting an organisation to a GitHub App installation, in progress.
 * It survives the trip to github.com and back, where the session cookie isn't sent.
 * Only a hash of the state is stored.
 */
@Entity
@Table(name = "github_connect_requests", indexes =
    @Index(name = "idx_github_connect_state", columnList = "state_hash", unique = true))
@Data
public class GithubConnectRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "state_hash", nullable = false, unique = true, length = 128)
    private String stateHash;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    /** What GitHub's redirect claimed. Not trusted until the user is shown to have access to it. */
    @Column(name = "installation_id")
    private Long installationId;

    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    @Column(name = "completed_at")
    private OffsetDateTime completedAt;
}
