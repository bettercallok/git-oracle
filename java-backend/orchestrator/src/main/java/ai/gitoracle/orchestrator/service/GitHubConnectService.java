package ai.gitoracle.orchestrator.service;

import ai.gitoracle.core.model.postgres.GithubConnectRequest;
import ai.gitoracle.core.model.postgres.Tenant;
import ai.gitoracle.core.model.postgres.UserAccount;
import ai.gitoracle.core.security.ApiKeys;
import ai.gitoracle.orchestrator.auth.GitHubOAuthClient;
import ai.gitoracle.orchestrator.repository.GithubConnectRequestRepository;
import ai.gitoracle.orchestrator.repository.UserAccountRepository;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

/**
 * Connects an organisation to its GitHub App installation.
 *
 * <ol>
 *   <li>An owner asks to connect: a request is stored, and they go to GitHub to install the App.</li>
 *   <li>GitHub redirects to the Setup URL with an {@code installation_id}. Anyone can type
 *       that into a URL, so it proves nothing, and they're sent to sign in with GitHub.</li>
 *   <li>The sign-in gives a token for the person, used once to ask GitHub which
 *       installations they can really access. It must be the same person who started,
 *       and the installation must be among them. Only then is it connected.</li>
 * </ol>
 */
@Service
public class GitHubConnectService {

    private static final Logger logger = LoggerFactory.getLogger(GitHubConnectService.class);
    private static final Duration REQUEST_LIFETIME = Duration.ofMinutes(30);
    private static final SecureRandom RANDOM = new SecureRandom();

    /** What the dashboard is told, in its redirect. */
    public enum Outcome { CONNECTED, FAILED, WRONG_USER, NOT_YOURS, TAKEN, REQUESTED }

    private final GithubConnectRequestRepository requests;
    private final UserAccountRepository users;
    private final OrganizationService organizations;
    private final GitHubInstallations installations;
    private final GitHubOAuthClient github;
    private final AuditLog audit;
    private final EntityManager entityManager;
    private final String appSlug;

    public GitHubConnectService(GithubConnectRequestRepository requests, UserAccountRepository users,
                                OrganizationService organizations, GitHubInstallations installations,
                                GitHubOAuthClient github, AuditLog audit, EntityManager entityManager,
                                @Value("${gitoracle.auth.github-app-slug:}") String appSlug) {
        this.requests = requests;
        this.users = users;
        this.organizations = organizations;
        this.installations = installations;
        this.github = github;
        this.audit = audit;
        this.entityManager = entityManager;
        this.appSlug = appSlug;
    }

    public boolean isAvailable() {
        return appSlug != null && !appSlug.isBlank() && github.isConfigured();
    }

    /** Step 1. @return the GitHub page to send the owner to */
    @Transactional
    public String start(UUID tenantId, OrganizationService.Actor actor) {
        organizations.requireOwner(tenantId, actor);
        if (actor.userId() == null) {
            throw new OrganizationService.OrganizationException(HttpStatus.FORBIDDEN, "This requires signing in with GitHub.");
        }
        if (!isAvailable()) {
            throw new OrganizationService.OrganizationException(HttpStatus.CONFLICT,
                "This server isn't set up for connecting GitHub: GITHUB_APP_SLUG is missing.");
        }
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String state = HexFormat.of().formatHex(bytes);

        GithubConnectRequest request = new GithubConnectRequest();
        request.setStateHash(ApiKeys.hash(state));
        request.setTenantId(tenantId);
        request.setUserId(actor.userId());
        request.setExpiresAt(OffsetDateTime.now().plus(REQUEST_LIFETIME));
        requests.save(request);
        return github.installUrl(appSlug, state);
    }

    private Optional<GithubConnectRequest> open(String state) {
        if (state == null || state.isBlank()) return Optional.empty();
        return requests.findByStateHash(ApiKeys.hash(state))
            .filter(request -> request.getCompletedAt() == null)
            .filter(request -> request.getExpiresAt().isAfter(OffsetDateTime.now()));
    }

    /** True if this state belongs to a connection in progress rather than a sign-in. */
    public boolean isConnecting(String state) {
        return open(state).isPresent();
    }

    /**
     * Step 2: GitHub's redirect after installing.
     *
     * @return empty to continue to sign-in, or why this ends here
     */
    @Transactional
    public Optional<Outcome> recordInstallation(String state, String installationId, String setupAction) {
        GithubConnectRequest request = open(state).orElse(null);
        if (request == null) return Optional.of(Outcome.FAILED);
        // An organisation member without permission to install can only request it.
        if ("request".equals(setupAction)) return Optional.of(Outcome.REQUESTED);
        try {
            request.setInstallationId(Long.valueOf(installationId));
        } catch (NumberFormatException e) {
            return Optional.of(Outcome.FAILED);
        }
        requests.save(request);
        return Optional.empty();
    }

    /** Step 3: the sign-in that proves who this is and what they can access. */
    @Transactional
    public Outcome complete(String state, GitHubOAuthClient.UserInstallations proof) {
        GithubConnectRequest request = open(state).orElse(null);
        if (request == null || request.getInstallationId() == null) return Outcome.FAILED;
        request.setCompletedAt(OffsetDateTime.now());
        requests.save(request);

        UserAccount starter = users.findById(request.getUserId()).orElse(null);
        if (starter == null || starter.getGithubId() != proof.user().id()) {
            logger.warn("GitHub connection refused: started by user {} but finished by GitHub account {}",
                request.getUserId(), proof.user().login());
            return Outcome.WRONG_USER;
        }
        if (!proof.installationIds().contains(request.getInstallationId())) {
            logger.warn("GitHub connection refused: {} has no access to installation {}",
                proof.user().login(), request.getInstallationId());
            return Outcome.NOT_YOURS;
        }
        // The starter was an owner when they began; that may have changed since.
        organizations.requireOwner(request.getTenantId(), new OrganizationService.Actor(request.getUserId(), false));

        String installation = String.valueOf(request.getInstallationId());
        if (installations.isTakenByAnother(request.getTenantId(), installation)) {
            return Outcome.TAKEN;
        }
        Tenant tenant = entityManager.find(Tenant.class, request.getTenantId());
        if (tenant == null) return Outcome.FAILED;
        tenant.setGithubAppInstallationId(installation);
        audit.record(request.getTenantId(), request.getUserId(), "github.connected", installation, null);
        return Outcome.CONNECTED;
    }

    @Transactional
    public void disconnect(UUID tenantId, OrganizationService.Actor actor) {
        organizations.requireOwner(tenantId, actor);
        Tenant tenant = entityManager.find(Tenant.class, tenantId);
        if (tenant != null && tenant.getGithubAppInstallationId() != null) {
            String old = tenant.getGithubAppInstallationId();
            tenant.setGithubAppInstallationId(null);
            audit.record(tenantId, "github.disconnected", old, null);
        }
    }
}
