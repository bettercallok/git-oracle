package ai.gitoracle.orchestrator.service;

import ai.gitoracle.core.model.postgres.AllowedRepository;
import ai.gitoracle.core.model.postgres.Tenant;
import ai.gitoracle.core.security.RepoNames;
import ai.gitoracle.orchestrator.repository.AllowedRepositoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * Which repositories a tenant may use (security audit D1).
 *
 * <p>All tenants share one GitHub App installation, so before this any API key
 * could read the commits and diffs of any repository the installation can see,
 * private ones included, and send a job's pull request to any of them.
 * Tenancy covered GitOracle's own rows but not the GitHub resources they point
 * at. Now a repository is usable by a tenant only if it is on that tenant's
 * list — an empty list allows nothing.
 *
 * <p>{@code GITORACLE_ALLOWED_REPOS} (comma-separated {@code owner/name} or
 * GitHub URLs) seeds the default tenant's list at startup, so a single-tenant
 * install and CI work without calling the admin API. Seeding only adds.
 */
@Service
public class RepoAccessService {

    private static final Logger logger = LoggerFactory.getLogger(RepoAccessService.class);

    private final AllowedRepositoryRepository allowed;
    private final String seed;

    public RepoAccessService(AllowedRepositoryRepository allowed,
                             @Value("${gitoracle.allowed-repos:}") String seed) {
        this.allowed = allowed;
        this.seed = seed;
    }

    public boolean isAllowed(UUID tenantId, String repo) {
        String slug = RepoNames.slug(repo);
        return tenantId != null && slug != null && allowed.existsByTenantIdAndRepo(tenantId, slug);
    }

    /** @throws RepoNotAllowedException if {@code repo} isn't on the tenant's list. */
    public void requireAllowed(UUID tenantId, String repo) {
        if (!isAllowed(tenantId, repo)) {
            String slug = RepoNames.slug(repo);
            throw new RepoNotAllowedException("Repository " + (slug != null ? slug : "'" + repo + "'")
                + " is not on this tenant's allow-list. An admin can add it with POST /api/v1/admin/repos.");
        }
    }

    public List<String> list(UUID tenantId) {
        return allowed.findByTenantIdOrderByRepo(tenantId).stream().map(AllowedRepository::getRepo).toList();
    }

    /** Adds {@code repo}; returns its slug. Idempotent. */
    public String add(UUID tenantId, String repo) {
        String slug = RepoNames.slug(repo);
        if (slug == null) {
            throw new IllegalArgumentException("Not a GitHub repository: '" + repo + "' (use owner/name)");
        }
        if (!allowed.existsByTenantIdAndRepo(tenantId, slug)) {
            AllowedRepository row = new AllowedRepository();
            row.setTenantId(tenantId);
            row.setRepo(slug);
            try {
                allowed.save(row);
            } catch (DataIntegrityViolationException raced) {
                // Added concurrently — the unique constraint makes that a no-op.
            }
        }
        return slug;
    }

    /** Removes {@code repo}; true if it was on the list. */
    public boolean remove(UUID tenantId, String repo) {
        String slug = RepoNames.slug(repo);
        return slug != null && allowed.deleteByTenantIdAndRepo(tenantId, slug) > 0;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void seedDefaultTenant() {
        if (seed == null || seed.isBlank()) {
            return;
        }
        for (String entry : seed.split(",")) {
            if (entry.isBlank()) continue;
            try {
                logger.info("Allow-list: default tenant may use {}", add(Tenant.DEFAULT_ID, entry));
            } catch (IllegalArgumentException e) {
                logger.warn("Ignoring GITORACLE_ALLOWED_REPOS entry: {}", e.getMessage());
            }
        }
    }
}
