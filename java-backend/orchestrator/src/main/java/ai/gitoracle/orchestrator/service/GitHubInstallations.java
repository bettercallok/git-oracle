package ai.gitoracle.orchestrator.service;

import ai.gitoracle.core.model.postgres.Tenant;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;

/**
 * Which GitHub App installation an organisation acts through.
 *
 * <p>An installation can see every repository it was installed on, private ones
 * included, so using one shared installation meant the allow-list was all that kept
 * organisations out of each other's code. Now each has its own. Only the default
 * organisation falls back to GITHUB_INSTALLATION_ID, which keeps a single-operator
 * install working. Everyone else gets nothing until they connect.
 */
@Service
public class GitHubInstallations {

    /** The default tenant's row was seeded with this made-up value, which is not an installation. */
    private static final String OLD_PLACEHOLDER = "123456";

    private final EntityManager entityManager;
    private final GitHubClientService github;

    public GitHubInstallations(EntityManager entityManager, GitHubClientService github) {
        this.entityManager = entityManager;
        this.github = github;
    }

    public Optional<String> find(UUID tenantId) {
        Tenant tenant = entityManager.find(Tenant.class, tenantId);
        String own = tenant == null ? null : tenant.getGithubAppInstallationId();
        if (own != null && !own.isBlank() && !OLD_PLACEHOLDER.equals(own)) {
            return Optional.of(own);
        }
        if (Tenant.DEFAULT_ID.equals(tenantId)) {
            String shared = github.defaultInstallationId();
            if (shared != null && !shared.isBlank()) return Optional.of(shared);
        }
        return Optional.empty();
    }

    /** True when this is the configured GITHUB_INSTALLATION_ID rather than the organisation's own. */
    public boolean usesSharedInstallation(UUID tenantId) {
        return find(tenantId).filter(id -> id.equals(github.defaultInstallationId())).isPresent();
    }

    /** @throws GitHubNotConnectedException if the organisation has no installation */
    public String require(UUID tenantId) {
        return find(tenantId).orElseThrow(GitHubNotConnectedException::new);
    }

    /** True if another organisation already uses this installation, which would let it reach their code. */
    public boolean isTakenByAnother(UUID tenantId, String installationId) {
        if (installationId.equals(github.defaultInstallationId()) && !Tenant.DEFAULT_ID.equals(tenantId)) {
            return true;
        }
        Long others = entityManager.createQuery(
                "SELECT COUNT(t) FROM Tenant t WHERE t.githubAppInstallationId = :installation AND t.id <> :id", Long.class)
            .setParameter("installation", installationId)
            .setParameter("id", tenantId)
            .getSingleResult();
        return others > 0;
    }
}
