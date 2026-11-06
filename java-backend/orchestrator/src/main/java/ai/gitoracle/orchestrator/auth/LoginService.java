package ai.gitoracle.orchestrator.auth;

import ai.gitoracle.core.model.postgres.ApiKey;
import ai.gitoracle.core.model.postgres.Tenant;
import ai.gitoracle.core.model.postgres.UserAccount;
import ai.gitoracle.core.model.postgres.UserSession;
import ai.gitoracle.core.security.SessionTokens;
import ai.gitoracle.orchestrator.repository.UserAccountRepository;
import ai.gitoracle.orchestrator.repository.UserSessionRepository;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * Turns a verified GitHub identity into a user and a session.
 *
 * <p>An admin works in the default tenant, which is where a single-operator
 * install's jobs and allow-list already live. Everyone else gets a tenant of
 * their own, empty until an admin adds repositories to its allow-list.
 */
@Service
public class LoginService {

    private static final Logger logger = LoggerFactory.getLogger(LoginService.class);

    /** How long an expired session row is kept for reference before it is deleted. */
    private static final Duration EXPIRED_SESSION_RETENTION = Duration.ofDays(30);

    public record IssuedSession(String token, Duration ttl) {}

    private final LoginPolicy policy;
    private final UserAccountRepository users;
    private final UserSessionRepository sessions;
    private final EntityManager entityManager;
    private final Duration sessionTtl;

    public LoginService(LoginPolicy policy,
                        UserAccountRepository users,
                        UserSessionRepository sessions,
                        EntityManager entityManager,
                        @Value("${gitoracle.auth.session-ttl:P7D}") Duration sessionTtl) {
        this.policy = policy;
        this.users = users;
        this.sessions = sessions;
        this.entityManager = entityManager;
        this.sessionTtl = sessionTtl;
    }

    /** @return the new session, or empty if this GitHub account isn't invited */
    @Transactional
    public Optional<IssuedSession> signIn(GitHubOAuthClient.GitHubUser gitHubUser) {
        LoginPolicy.Decision decision = policy.decide(gitHubUser.id(), gitHubUser.login());
        if (!decision.allowed()) {
            logger.warn("Sign-in refused: GitHub account {} (id {}) is not invited", gitHubUser.login(), gitHubUser.id());
            return Optional.empty();
        }

        OffsetDateTime now = OffsetDateTime.now();
        UserAccount user = users.findByGithubId(gitHubUser.id()).orElseGet(() -> {
            UserAccount created = new UserAccount();
            created.setGithubId(gitHubUser.id());
            created.setCreatedAt(now);
            return created;
        });
        user.setGithubLogin(gitHubUser.login());
        user.setLastLoginAt(now);
        user.setTenantId(tenantFor(user, gitHubUser.id(), decision.admin()));
        user = users.save(user);

        SessionTokens.GeneratedToken token = SessionTokens.generate();
        UserSession session = new UserSession();
        session.setTokenHash(token.hash());
        session.setUserId(user.getId());
        session.setTenantId(user.getTenantId());
        session.setScopes(ApiKey.Scopes.join(decision.scopes()));
        session.setCreatedAt(now);
        session.setExpiresAt(now.plus(sessionTtl));
        sessions.save(session);

        sessions.deleteByExpiresAtBefore(now.minus(EXPIRED_SESSION_RETENTION));

        logger.info("GitHub account {} signed in (tenant {}, admin={})",
            gitHubUser.login(), user.getTenantId(), decision.admin());
        return Optional.of(new IssuedSession(token.token(), sessionTtl));
    }

    /**
     * An admin who loses the role must also leave the default tenant, so a user
     * in that tenant who is no longer an admin gets a tenant of their own.
     */
    private UUID tenantFor(UserAccount user, long githubId, boolean admin) {
        if (admin) return Tenant.DEFAULT_ID;
        if (user.getTenantId() != null && !Tenant.DEFAULT_ID.equals(user.getTenantId())) {
            return user.getTenantId();
        }
        Tenant tenant = new Tenant();
        // The id, because a login can be renamed and later claimed by someone else.
        tenant.setOrgName("github:" + githubId);
        entityManager.persist(tenant);
        return tenant.getId();
    }

    /** Idempotent. Ids that match no active session are ignored. */
    @Transactional
    public void signOut(UUID sessionId) {
        sessions.findById(sessionId).ifPresent(session -> {
            if (session.getRevokedAt() == null) {
                session.setRevokedAt(OffsetDateTime.now());
                sessions.save(session);
            }
        });
    }

    /**
     * The invite and admin lists are environment settings, so they only change
     * across a restart. Applying them here means removing someone takes effect
     * then, rather than when their session happens to expire.
     */
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void revokeSessionsNoLongerAllowed() {
        OffsetDateTime now = OffsetDateTime.now();
        for (UserSession session : sessions.findByRevokedAtIsNullAndExpiresAtAfter(now)) {
            Optional<UserAccount> user = users.findById(session.getUserId());
            boolean stillValid = user.map(u -> {
                LoginPolicy.Decision decision = policy.decide(u.getGithubId(), u.getGithubLogin());
                return decision.allowed() && decision.scopes().equals(ApiKey.Scopes.parse(session.getScopes()));
            }).orElse(false);
            if (!stillValid) {
                session.setRevokedAt(now);
                sessions.save(session);
                logger.info("Revoked session {}: its user's invite or admin role changed", session.getId());
            }
        }
    }
}
