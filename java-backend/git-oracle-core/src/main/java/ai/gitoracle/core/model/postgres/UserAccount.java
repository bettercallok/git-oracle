package ai.gitoracle.core.model.postgres;

import jakarta.persistence.*;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A person who has signed in with GitHub.
 *
 * <p>Identified by GitHub's numeric id, not the login: a login can be renamed
 * and then claimed by someone else, and that person must not inherit this row's
 * tenant. {@link #githubLogin} is only a label, refreshed on every sign-in.
 */
@Entity
@Table(name = "users", indexes = {
    @Index(name = "idx_users_github_id", columnList = "github_id", unique = true)
})
@Data
public class UserAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "github_id", nullable = false, unique = true)
    private Long githubId;

    @Column(name = "github_login", nullable = false)
    private String githubLogin;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "last_login_at")
    private OffsetDateTime lastLoginAt;
}
