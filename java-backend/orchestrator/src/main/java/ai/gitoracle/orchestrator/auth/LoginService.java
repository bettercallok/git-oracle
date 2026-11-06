package ai.gitoracle.orchestrator.auth;

import ai.gitoracle.core.model.postgres.ApiKey;
import ai.gitoracle.core.model.postgres.OrgInvitation;
import ai.gitoracle.core.model.postgres.OrgMembership;
import ai.gitoracle.core.model.postgres.OrgRole;
import ai.gitoracle.core.model.postgres.Tenant;
import ai.gitoracle.core.model.postgres.UserAccount;
import ai.gitoracle.core.model.postgres.UserSession;
import ai.gitoracle.core.security.SessionTokens;
import ai.gitoracle.orchestrator.repository.OrgInvitationRepository;
import ai.gitoracle.orchestrator.repository.OrgMembershipRepository;
import ai.gitoracle.orchestrator.repository.UserAccountRepository;
import ai.gitoracle.orchestrator.repository.UserSessionRepository;
import ai.gitoracle.orchestrator.service.AuditLog;
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
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Turns a verified GitHub identity into a user, their organisations and a session.
 *
 * <p>Someone may sign in if they run the platform, are on the operator's invite
 * list, or have been invited to an organisation. A platform admin works in the
 * default tenant, which is where a single-operator install's jobs live. Someone on
 * the invite list gets an organisation of their own, empty until a platform admin
 * adds repositories. Invitations are accepted automatically at sign-in.
 *
 * <p>A membership that came from the operator's lists rather than an invitation
 * lasts only while those lists still allow it, so removing someone from a list and
 * restarting still removes their access.
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
    private final OrgMembershipRepository memberships;
    private final OrgInvitationRepository invitations;
    private final AuditLog audit;
    private final EntityManager entityManager;
    private final Duration sessionTtl;

    public LoginService(LoginPolicy policy,
                        UserAccountRepository users,
                        UserSessionRepository sessions,
                        OrgMembershipRepository memberships,
                        OrgInvitationRepository invitations,
                        AuditLog audit,
                        EntityManager entityManager,
                        @Value("${gitoracle.auth.session-ttl:P7D}") Duration sessionTtl) {
        this.policy = policy;
        this.users = users;
        this.sessions = sessions;
        this.memberships = memberships;
        this.invitations = invitations;
        this.audit = audit;
        this.entityManager = entityManager;
        this.sessionTtl = sessionTtl;
    }

    /** @return the new session, or empty if this GitHub account has no access */
    @Transactional
    public Optional<IssuedSession> signIn(GitHubOAuthClient.GitHubUser gitHubUser) {
        OffsetDateTime now = OffsetDateTime.now();
        boolean admin = policy.isPlatformAdmin(gitHubUser.id());
        boolean listed = policy.isInvited(gitHubUser.login());
        List<OrgInvitation> pending =
            invitations.findByGithubIdAndAcceptedAtIsNullAndRevokedAtIsNullAndExpiresAtAfter(gitHubUser.id(), now);

        Optional<UserAccount> existing = users.findByGithubId(gitHubUser.id());
        boolean hasInvitedMembership = existing
            .map(user -> memberships.findByUserId(user.getId()).stream().anyMatch(this::isInvitedRow))
            .orElse(false);
        if (!admin && !listed && pending.isEmpty() && !hasInvitedMembership) {
            logger.warn("Sign-in refused: GitHub account {} (id {}) has no access", gitHubUser.login(), gitHubUser.id());
            return Optional.empty();
        }

        UserAccount user = existing.orElseGet(() -> {
            UserAccount created = new UserAccount();
            created.setGithubId(gitHubUser.id());
            created.setCreatedAt(now);
            // Replaced below; the column can't be null and the row needs an id first.
            created.setTenantId(Tenant.DEFAULT_ID);
            return created;
        });
        user.setGithubLogin(gitHubUser.login());
        user.setLastLoginAt(now);
        user = users.save(user);

        for (OrgInvitation invitation : pending) {
            accept(invitation, user, now);
        }
        if (admin && memberships.findByTenantIdAndUserId(Tenant.DEFAULT_ID, user.getId()).isEmpty()) {
            addMember(Tenant.DEFAULT_ID, user.getId(), OrgRole.OWNER, null, now);
        }

        List<OrgMembership> usable = usableMemberships(user, admin, listed);
        if (usable.isEmpty()) {
            if (!listed) return Optional.empty();
            Tenant tenant = new Tenant();
            // The id, because a login can be renamed and later claimed by someone else. The
            // suffix because someone removed from their first organisation can get another,
            // and the name is unique.
            tenant.setOrgName("github:" + gitHubUser.id() + "-" + UUID.randomUUID().toString().substring(0, 8));
            entityManager.persist(tenant);
            audit.record(tenant.getId(), user.getId(), "organization.created", tenant.getOrgName(), null);
            usable = List.of(addMember(tenant.getId(), user.getId(), OrgRole.OWNER, null, now));
        }

        UUID lastUsed = user.getTenantId();
        OrgMembership active = usable.stream().filter(m -> m.getTenantId().equals(lastUsed)).findFirst().orElse(usable.get(0));
        user.setTenantId(active.getTenantId());
        users.save(user);

        SessionTokens.GeneratedToken token = SessionTokens.generate();
        UserSession session = new UserSession();
        session.setTokenHash(token.hash());
        session.setUserId(user.getId());
        session.setTenantId(active.getTenantId());
        session.setScopes(ApiKey.Scopes.join(active.getRole().sessionScopes(admin)));
        session.setCreatedAt(now);
        session.setExpiresAt(now.plus(sessionTtl));
        sessions.save(session);

        sessions.deleteByExpiresAtBefore(now.minus(EXPIRED_SESSION_RETENTION));

        logger.info("GitHub account {} signed in (organisation {}, role {}, platform admin={})",
            gitHubUser.login(), active.getTenantId(), active.getRole(), admin);
        return Optional.of(new IssuedSession(token.token(), sessionTtl));
    }

    /** Moves a session to another organisation the person belongs to. */
    @Transactional
    public boolean switchOrganization(UUID sessionId, UUID tenantId) {
        UserSession session = sessions.findById(sessionId).orElse(null);
        if (session == null || session.getRevokedAt() != null) return false;
        UserAccount user = users.findById(session.getUserId()).orElse(null);
        if (user == null) return false;

        boolean admin = policy.isPlatformAdmin(user.getGithubId());
        OrgMembership target = usableMemberships(user, admin, policy.isInvited(user.getGithubLogin())).stream()
            .filter(m -> m.getTenantId().equals(tenantId)).findFirst().orElse(null);
        if (target == null) return false;

        session.setTenantId(tenantId);
        session.setScopes(ApiKey.Scopes.join(target.getRole().sessionScopes(admin)));
        sessions.save(session);
        user.setTenantId(tenantId);
        users.save(user);
        return true;
    }

    /** The organisations a person can act in right now, for the dashboard's switcher. */
    @Transactional(readOnly = true)
    public List<OrgMembership> organizationsOf(UUID userId) {
        return users.findById(userId)
            .map(user -> usableMemberships(user, policy.isPlatformAdmin(user.getGithubId()), policy.isInvited(user.getGithubLogin())))
            .orElse(List.of());
    }

    /**
     * Invitations always count. A membership from the operator's lists counts while
     * those lists allow it, and the default tenant's only for a platform admin, so a
     * former admin who's still on the invite list doesn't stay its owner.
     */
    private List<OrgMembership> usableMemberships(UserAccount user, boolean admin, boolean listed) {
        return memberships.findByUserId(user.getId()).stream()
            .filter(m -> isInvitedRow(m) || admin || (listed && !Tenant.DEFAULT_ID.equals(m.getTenantId())))
            .toList();
    }

    /** See {@link #accept}: a membership from an invitation always has an inviter set. */
    private boolean isInvitedRow(OrgMembership membership) {
        return membership.getInvitedByUserId() != null;
    }

    private void accept(OrgInvitation invitation, UserAccount user, OffsetDateTime now) {
        if (memberships.findByTenantIdAndUserId(invitation.getTenantId(), user.getId()).isEmpty()) {
            addMember(invitation.getTenantId(), user.getId(), invitation.getRole(), invitedByOrSelf(invitation, user), now);
        } else {
            // Already there through the operator's lists: now it no longer depends on them.
            memberships.findByTenantIdAndUserId(invitation.getTenantId(), user.getId())
                .filter(existing -> existing.getInvitedByUserId() == null)
                .ifPresent(existing -> {
                    existing.setInvitedByUserId(invitedByOrSelf(invitation, user));
                    memberships.save(existing);
                });
        }
        invitation.setAcceptedAt(now);
        invitations.save(invitation);
        audit.record(invitation.getTenantId(), user.getId(), "invitation.accepted", user.getGithubLogin(), "role=" + invitation.getRole());
    }

    /**
     * An invitation made with an API key has no inviter. The invited person stands in,
     * so the membership still counts as having come from an invitation.
     */
    private UUID invitedByOrSelf(OrgInvitation invitation, UserAccount user) {
        return invitation.getInvitedByUserId() != null ? invitation.getInvitedByUserId() : user.getId();
    }

    private OrgMembership addMember(UUID tenantId, UUID userId, OrgRole role, UUID invitedBy, OffsetDateTime now) {
        OrgMembership membership = new OrgMembership();
        membership.setTenantId(tenantId);
        membership.setUserId(userId);
        membership.setRole(role);
        membership.setInvitedByUserId(invitedBy);
        membership.setCreatedAt(now);
        return memberships.save(membership);
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
     * Every sign-in before organisations existed left a user with a tenant but no
     * membership. They become that organisation's owner, which is what they
     * effectively were.
     *
     * <p>Runs only while there are no memberships at all, meaning the one upgrade.
     * Checking each user instead would make someone who was removed from an
     * organisation its owner again at the next restart.
     */
    void moveExistingUsersIntoOrganizations() {
        if (memberships.count() > 0) return;
        OffsetDateTime now = OffsetDateTime.now();
        for (UserAccount user : users.findAll()) {
            addMember(user.getTenantId(), user.getId(), OrgRole.OWNER, null, now);
            audit.record(user.getTenantId(), null, "member.migrated", user.getGithubLogin(), "role=OWNER");
            logger.info("Made {} an owner of organisation {}", user.getGithubLogin(), user.getTenantId());
        }
    }

    /**
     * The admin and invite lists are environment settings, so they only change
     * across a restart. Applying them here means removing someone takes effect
     * then, rather than when their session happens to expire.
     */
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void applyOrganizationsAtStartup() {
        moveExistingUsersIntoOrganizations();
        OffsetDateTime now = OffsetDateTime.now();
        for (UserSession session : sessions.findByRevokedAtIsNullAndExpiresAtAfter(now)) {
            if (!stillValid(session)) {
                session.setRevokedAt(now);
                sessions.save(session);
                logger.info("Revoked session {}: its user's access changed", session.getId());
            }
        }
    }

    private boolean stillValid(UserSession session) {
        UserAccount user = users.findById(session.getUserId()).orElse(null);
        if (user == null) return false;
        boolean admin = policy.isPlatformAdmin(user.getGithubId());
        return usableMemberships(user, admin, policy.isInvited(user.getGithubLogin())).stream()
            .filter(m -> m.getTenantId().equals(session.getTenantId()))
            .anyMatch(m -> m.getRole().sessionScopes(admin).equals(ApiKey.Scopes.parse(session.getScopes())));
    }
}
