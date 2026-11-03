package ai.gitoracle.core.model.postgres;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One repository a tenant may use, as {@code owner/name} (see
 * {@link ai.gitoracle.core.security.RepoNames}).
 *
 * <p>Every tenant shares one GitHub App installation, and nothing used to
 * restrict which repositories a tenant's requests could name — so any API key
 * could read any installed repo's commits and diffs, private ones included, and
 * send a job's pull request to any of them. A repository is now usable by a
 * tenant only when it is on that tenant's list: jobs, the PR target, the
 * commit explorer and the risk graph are all checked against it. An empty
 * list allows nothing.
 */
@Entity
@Table(name = "tenant_allowed_repos",
       uniqueConstraints = @UniqueConstraint(name = "uk_tenant_allowed_repo", columnNames = {"tenant_id", "repo"}))
@Data
public class AllowedRepository {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "repo", nullable = false, length = 255)
    private String repo;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false,
            columnDefinition = "timestamptz DEFAULT CURRENT_TIMESTAMP")
    private OffsetDateTime createdAt;
}
