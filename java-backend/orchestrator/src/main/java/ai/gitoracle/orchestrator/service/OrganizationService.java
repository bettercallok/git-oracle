package ai.gitoracle.orchestrator.service;

import ai.gitoracle.core.model.postgres.ApiKey;
import ai.gitoracle.core.model.postgres.OrgInvitation;
import ai.gitoracle.core.model.postgres.OrgMembership;
import ai.gitoracle.core.model.postgres.OrgRole;
import ai.gitoracle.core.model.postgres.Tenant;
import ai.gitoracle.core.model.postgres.UserAccount;
import ai.gitoracle.core.model.postgres.UserSession;
import ai.gitoracle.orchestrator.auth.GitHubOAuthClient;
import ai.gitoracle.orchestrator.repository.ApiKeyRepository;
import ai.gitoracle.orchestrator.repository.OrgInvitationRepository;
import ai.gitoracle.orchestrator.repository.OrgMembershipRepository;
import ai.gitoracle.orchestrator.repository.UserAccountRepository;
import ai.gitoracle.orchestrator.repository.UserSessionRepository;
import jakarta.persistence.EntityManager;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * The rules for who may change whose access in an organisation.
 *
 * <ul>
 *   <li>Nobody can grant a role higher than their own, or change someone above them.</li>
 *   <li>An organisation always keeps at least one owner.</li>
 *   <li>Losing a role ends that person's sessions, and removal or viewer status also
 *       revokes the API keys they made, because those keys would otherwise keep the
 *       access they just lost.</li>
 *   <li>Every change is written to the audit log.</li>
 * </ul>
 */
@Service
public class OrganizationService {

    /** Who is acting. A platform admin ranks as an owner; an API key has no user. */
    public record Actor(UUID userId, boolean platformAdmin) {}

    public static class OrganizationException extends RuntimeException {
        private final HttpStatus status;

        public OrganizationException(HttpStatus status, String message) {
            super(message);
            this.status = status;
        }

        public HttpStatus status() {
            return status;
        }
    }

    static final Duration INVITATION_LIFETIME = Duration.ofDays(7);

    private final OrgMembershipRepository memberships;
    private final OrgInvitationRepository invitations;
    private final UserAccountRepository users;
    private final UserSessionRepository sessions;
    private final ApiKeyRepository apiKeys;
    private final GitHubOAuthClient github;
    private final AuditLog audit;
    private final EntityManager entityManager;

    public OrganizationService(OrgMembershipRepository memberships, OrgInvitationRepository invitations,
                               UserAccountRepository users, UserSessionRepository sessions, ApiKeyRepository apiKeys,
                               GitHubOAuthClient github, AuditLog audit, EntityManager entityManager) {
        this.memberships = memberships;
        this.invitations = invitations;
        this.users = users;
        this.sessions = sessions;
        this.apiKeys = apiKeys;
        this.github = github;
        this.audit = audit;
        this.entityManager = entityManager;
    }

    @Transactional
    public void rename(UUID tenantId, Actor actor, String name) {
        requireRank(tenantId, actor, OrgRole.ADMIN);
        Tenant tenant = entityManager.find(Tenant.class, tenantId);
        if (tenant == null) {
            throw new OrganizationException(HttpStatus.NOT_FOUND, "No such organisation.");
        }
        Long taken = entityManager.createQuery("SELECT COUNT(t) FROM Tenant t WHERE t.orgName = :name AND t.id <> :id", Long.class)
            .setParameter("name", name)
            .setParameter("id", tenantId)
            .getSingleResult();
        if (taken > 0) {
            throw new OrganizationException(HttpStatus.CONFLICT, "Another organisation already has that name.");
        }
        String old = tenant.getOrgName();
        tenant.setOrgName(name);
        audit.record(tenantId, "organization.renamed", name, "was " + old);
    }

    @Transactional
    public OrgInvitation invite(UUID tenantId, Actor actor, String githubLogin, OrgRole role) {
        requireRank(tenantId, actor, role);
        if (entityManager.find(Tenant.class, tenantId) == null) {
            throw new OrganizationException(HttpStatus.NOT_FOUND, "No such organisation.");
        }
        GitHubOAuthClient.GitHubUser invited = github.lookupUser(githubLogin)
            .orElseThrow(() -> new OrganizationException(HttpStatus.BAD_REQUEST, "No GitHub account named " + githubLogin + "."));

        users.findByGithubId(invited.id())
            .flatMap(user -> memberships.findByTenantIdAndUserId(tenantId, user.getId()))
            .ifPresent(existing -> {
                throw new OrganizationException(HttpStatus.CONFLICT, invited.login() + " is already a member.");
            });

        // A new invitation replaces any earlier one, so there's only ever one role on offer.
        OffsetDateTime now = OffsetDateTime.now();
        for (OrgInvitation earlier : invitations.findByTenantIdAndGithubIdAndAcceptedAtIsNullAndRevokedAtIsNull(tenantId, invited.id())) {
            earlier.setRevokedAt(now);
            invitations.save(earlier);
        }

        OrgInvitation invitation = new OrgInvitation();
        invitation.setTenantId(tenantId);
        invitation.setGithubId(invited.id());
        invitation.setGithubLogin(invited.login());
        invitation.setRole(role);
        invitation.setInvitedByUserId(actor.userId());
        invitation.setCreatedAt(now);
        invitation.setExpiresAt(now.plus(INVITATION_LIFETIME));
        invitation = invitations.save(invitation);
        audit.record(tenantId, "invitation.created", invited.login(), "role=" + role);
        return invitation;
    }

    @Transactional
    public void revokeInvitation(UUID tenantId, Actor actor, UUID invitationId) {
        OrgInvitation invitation = invitations.findById(invitationId)
            .filter(found -> found.getTenantId().equals(tenantId))
            .orElseThrow(() -> new OrganizationException(HttpStatus.NOT_FOUND, "No such invitation."));
        requireRank(tenantId, actor, invitation.getRole());
        if (invitation.getAcceptedAt() != null) {
            throw new OrganizationException(HttpStatus.CONFLICT, "That invitation was already accepted.");
        }
        if (invitation.getRevokedAt() == null) {
            invitation.setRevokedAt(OffsetDateTime.now());
            invitations.save(invitation);
            audit.record(tenantId, "invitation.revoked", invitation.getGithubLogin(), null);
        }
    }

    @Transactional
    public void changeRole(UUID tenantId, Actor actor, UUID userId, OrgRole newRole) {
        OrgMembership member = requireMember(tenantId, userId);
        OrgRole oldRole = member.getRole();
        // Both ends: you can't demote someone above you, or promote anyone above yourself.
        requireRank(tenantId, actor, oldRole);
        requireRank(tenantId, actor, newRole);
        if (oldRole == newRole) return;
        if (oldRole == OrgRole.OWNER) requireAnotherOwner(tenantId, userId);

        member.setRole(newRole);
        memberships.save(member);
        endSessions(tenantId, userId);
        if (newRole == OrgRole.VIEWER) revokeKeys(tenantId, userId);
        audit.record(tenantId, "member.role_changed", login(userId), oldRole + " -> " + newRole);
    }

    @Transactional
    public void removeMember(UUID tenantId, Actor actor, UUID userId) {
        OrgMembership member = requireMember(tenantId, userId);
        requireRank(tenantId, actor, member.getRole());
        if (member.getRole() == OrgRole.OWNER) requireAnotherOwner(tenantId, userId);

        memberships.delete(member);
        endSessions(tenantId, userId);
        revokeKeys(tenantId, userId);
        audit.record(tenantId, "member.removed", login(userId), "was " + member.getRole());
    }

    private OrgMembership requireMember(UUID tenantId, UUID userId) {
        return memberships.findByTenantIdAndUserId(tenantId, userId)
            .orElseThrow(() -> new OrganizationException(HttpStatus.NOT_FOUND, "No such member."));
    }

    /** Connecting GitHub decides what the whole organisation can reach, so only an owner may. */
    public void requireOwner(UUID tenantId, Actor actor) {
        requireRank(tenantId, actor, OrgRole.OWNER);
    }

    /** Refuses unless the actor's own role in this organisation is at least {@code needed}. */
    private void requireRank(UUID tenantId, Actor actor, OrgRole needed) {
        if (actor.platformAdmin()) return;
        OrgRole own = actor.userId() == null ? null
            : memberships.findByTenantIdAndUserId(tenantId, actor.userId()).map(OrgMembership::getRole).orElse(null);
        if (own == null || !own.atLeast(OrgRole.ADMIN) || !own.atLeast(needed)) {
            throw new OrganizationException(HttpStatus.FORBIDDEN, "Your role can't make that change.");
        }
    }

    private void requireAnotherOwner(UUID tenantId, UUID leaving) {
        boolean another = memberships.lockByTenantAndRole(tenantId, OrgRole.OWNER).stream()
            .anyMatch(owner -> !owner.getUserId().equals(leaving));
        if (!another) {
            throw new OrganizationException(HttpStatus.CONFLICT, "An organisation must keep at least one owner.");
        }
    }

    private void endSessions(UUID tenantId, UUID userId) {
        OffsetDateTime now = OffsetDateTime.now();
        for (UserSession session : sessions.findByUserIdAndTenantIdAndRevokedAtIsNull(userId, tenantId)) {
            session.setRevokedAt(now);
            sessions.save(session);
        }
    }

    private void revokeKeys(UUID tenantId, UUID userId) {
        OffsetDateTime now = OffsetDateTime.now();
        for (ApiKey key : apiKeys.findByTenantIdAndCreatedByUserIdAndRevokedAtIsNull(tenantId, userId)) {
            key.setRevokedAt(now);
            apiKeys.save(key);
            audit.record(tenantId, "api_key.revoked", key.getKeyPrefix(), "its creator lost access");
        }
    }

    private String login(UUID userId) {
        return users.findById(userId).map(UserAccount::getGithubLogin).orElse(userId.toString());
    }
}
