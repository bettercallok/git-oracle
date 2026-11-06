package ai.gitoracle.orchestrator.auth;

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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class LoginServiceTest {

    private static final long ADMIN_ID = 42;
    private static final UUID NEW_TENANT = UUID.randomUUID();
    private static final UUID OTHER_ORG = UUID.randomUUID();

    private UserAccountRepository users;
    private UserSessionRepository sessions;
    private OrgMembershipRepository memberships;
    private OrgInvitationRepository invitations;
    private EntityManager entityManager;
    private LoginService service;
    private final List<OrgMembership> rows = new ArrayList<>();

    @BeforeEach
    void setUp() {
        users = mock(UserAccountRepository.class);
        sessions = mock(UserSessionRepository.class);
        memberships = mock(OrgMembershipRepository.class);
        invitations = mock(OrgInvitationRepository.class);
        entityManager = mock(EntityManager.class);
        when(users.findByGithubId(any())).thenReturn(Optional.empty());
        when(users.save(any())).thenAnswer(call -> {
            UserAccount user = call.getArgument(0);
            if (user.getId() == null) user.setId(UUID.randomUUID());
            return user;
        });
        when(memberships.save(any())).thenAnswer(call -> {
            OrgMembership row = call.getArgument(0);
            if (!rows.contains(row)) rows.add(row);
            return row;
        });
        when(memberships.findByUserId(any())).thenAnswer(call ->
            rows.stream().filter(row -> row.getUserId().equals(call.getArgument(0))).toList());
        when(memberships.findByTenantIdAndUserId(any(), any())).thenAnswer(call -> rows.stream()
            .filter(row -> row.getTenantId().equals(call.getArgument(0)) && row.getUserId().equals(call.getArgument(1))).findFirst());
        when(memberships.count()).thenAnswer(call -> (long) rows.size());
        doAnswer(call -> {
            ((Tenant) call.getArgument(0)).setId(NEW_TENANT);
            return null;
        }).when(entityManager).persist(any(Tenant.class));
        service = new LoginService(new LoginPolicy("alice", String.valueOf(ADMIN_ID)), users, sessions, memberships,
            invitations, mock(AuditLog.class), entityManager, Duration.ofDays(7));
    }

    private UserSession savedSession() {
        ArgumentCaptor<UserSession> saved = ArgumentCaptor.forClass(UserSession.class);
        verify(sessions).save(saved.capture());
        return saved.getValue();
    }

    private static UserAccount account(long githubId, String login, UUID tenantId) {
        UserAccount user = new UserAccount();
        user.setId(UUID.randomUUID());
        user.setGithubId(githubId);
        user.setGithubLogin(login);
        user.setTenantId(tenantId);
        return user;
    }

    private void exists(UserAccount user) {
        when(users.findByGithubId(user.getGithubId())).thenReturn(Optional.of(user));
        when(users.findById(user.getId())).thenReturn(Optional.of(user));
    }

    private OrgMembership member(UserAccount user, UUID tenant, OrgRole role, UUID invitedBy) {
        OrgMembership row = new OrgMembership();
        row.setUserId(user.getId());
        row.setTenantId(tenant);
        row.setRole(role);
        row.setInvitedByUserId(invitedBy);
        rows.add(row);
        return row;
    }

    private void pendingInvitation(long githubId, UUID tenant, OrgRole role) {
        OrgInvitation invitation = new OrgInvitation();
        invitation.setGithubId(githubId);
        invitation.setTenantId(tenant);
        invitation.setRole(role);
        invitation.setInvitedByUserId(UUID.randomUUID());
        when(invitations.findByGithubIdAndAcceptedAtIsNullAndRevokedAtIsNullAndExpiresAtAfter(any(), any())).thenReturn(List.of(invitation));
    }

    @Test
    void someoneWithNoAccessGetsNoUserOrganisationOrSession() {
        assertThat(service.signIn(new GitHubOAuthClient.GitHubUser(7, "mallory"))).isEmpty();

        verify(users, never()).save(any());
        verify(sessions, never()).save(any());
        verify(entityManager, never()).persist(any());
    }

    @Test
    void someoneOnTheInviteListOwnsANewOrganisation() {
        service.signIn(new GitHubOAuthClient.GitHubUser(7, "alice"));

        UserSession session = savedSession();
        assertThat(session.getTenantId()).isEqualTo(NEW_TENANT);
        assertThat(session.getScopes()).contains("org:owner").doesNotContain("platform:admin");
    }

    @Test
    void onlyTheTokensHashIsStoredAndItExpires() {
        LoginService.IssuedSession issued = service.signIn(new GitHubOAuthClient.GitHubUser(7, "alice")).orElseThrow();

        UserSession session = savedSession();
        assertThat(session.getTokenHash()).isEqualTo(SessionTokens.hash(issued.token())).isNotEqualTo(issued.token());
        assertThat(session.getExpiresAt()).isBetween(
            OffsetDateTime.now().plusDays(7).minusMinutes(1), OffsetDateTime.now().plusDays(7).plusMinutes(1));
    }

    @Test
    void anInvitationLetsSomeoneInWithTheOfferedRoleAndNoOrganisationOfTheirOwn() {
        pendingInvitation(7, OTHER_ORG, OrgRole.VIEWER);

        service.signIn(new GitHubOAuthClient.GitHubUser(7, "new-person"));

        UserSession session = savedSession();
        assertThat(session.getTenantId()).isEqualTo(OTHER_ORG);
        assertThat(session.getScopes()).isEqualTo("jobs:read");
        verify(entityManager, never()).persist(any());
    }

    @Test
    void anInvitedMemberCanComeBackWithoutBeingOnAnyList() {
        UserAccount user = account(7, "new-person", OTHER_ORG);
        exists(user);
        member(user, OTHER_ORG, OrgRole.MEMBER, UUID.randomUUID());

        assertThat(service.signIn(new GitHubOAuthClient.GitHubUser(7, "new-person"))).isPresent();
        assertThat(savedSession().getScopes()).contains("jobs:write");
    }

    @Test
    void theLastUsedOrganisationIsWhereTheyLand() {
        UserAccount user = account(7, "person", OTHER_ORG);
        exists(user);
        member(user, UUID.randomUUID(), OrgRole.OWNER, UUID.randomUUID());
        member(user, OTHER_ORG, OrgRole.VIEWER, UUID.randomUUID());

        service.signIn(new GitHubOAuthClient.GitHubUser(7, "person"));

        assertThat(savedSession().getTenantId()).isEqualTo(OTHER_ORG);
    }

    @Test
    void aPlatformAdminOwnsTheDefaultOrganisation() {
        service.signIn(new GitHubOAuthClient.GitHubUser(ADMIN_ID, "operator"));

        UserSession session = savedSession();
        assertThat(session.getTenantId()).isEqualTo(Tenant.DEFAULT_ID);
        assertThat(session.getScopes()).contains("platform:admin", "org:owner");
    }

    @Test
    void aFormerAdminStillOnTheInviteListLeavesTheDefaultOrganisation() {
        UserAccount user = account(7, "alice", Tenant.DEFAULT_ID);
        exists(user);
        member(user, Tenant.DEFAULT_ID, OrgRole.OWNER, null);

        service.signIn(new GitHubOAuthClient.GitHubUser(7, "alice"));

        assertThat(savedSession().getTenantId()).isEqualTo(NEW_TENANT);
    }

    @Test
    void someoneRemovedFromTheInviteListLosesTheOrganisationItGaveThem() {
        UserAccount user = account(7, "no-longer-listed", OTHER_ORG);
        exists(user);
        member(user, OTHER_ORG, OrgRole.OWNER, null);

        assertThat(service.signIn(new GitHubOAuthClient.GitHubUser(7, "no-longer-listed"))).isEmpty();
    }

    @Test
    void switchingMovesTheSessionAndAppliesThatOrganisationsRole() {
        UserAccount user = account(7, "person", UUID.randomUUID());
        exists(user);
        member(user, OTHER_ORG, OrgRole.VIEWER, UUID.randomUUID());
        UserSession session = new UserSession();
        session.setUserId(user.getId());
        session.setScopes("jobs:read,jobs:write,org:admin");
        UUID sessionId = UUID.randomUUID();
        when(sessions.findById(sessionId)).thenReturn(Optional.of(session));

        assertThat(service.switchOrganization(sessionId, OTHER_ORG)).isTrue();

        assertThat(session.getTenantId()).isEqualTo(OTHER_ORG);
        assertThat(session.getScopes()).isEqualTo("jobs:read");
        assertThat(user.getTenantId()).isEqualTo(OTHER_ORG);
    }

    @Test
    void youCantSwitchIntoAnOrganisationYoureNotIn() {
        UserAccount user = account(7, "person", OTHER_ORG);
        exists(user);
        UserSession session = new UserSession();
        session.setUserId(user.getId());
        UUID sessionId = UUID.randomUUID();
        when(sessions.findById(sessionId)).thenReturn(Optional.of(session));

        assertThat(service.switchOrganization(sessionId, UUID.randomUUID())).isFalse();
        assertThat(session.getTenantId()).isNull();
    }

    @Test
    void aRevokedSessionCantSwitch() {
        UserSession session = new UserSession();
        session.setRevokedAt(OffsetDateTime.now());
        UUID sessionId = UUID.randomUUID();
        when(sessions.findById(sessionId)).thenReturn(Optional.of(session));

        assertThat(service.switchOrganization(sessionId, OTHER_ORG)).isFalse();
    }

    @Test
    void existingUsersBecomeOwnersOfTheirTenantOnceOnly() {
        UserAccount user = account(7, "alice", OTHER_ORG);
        when(users.findAll()).thenReturn(List.of(user));
        when(sessions.findByRevokedAtIsNullAndExpiresAtAfter(any())).thenReturn(List.of());

        service.applyOrganizationsAtStartup();
        service.applyOrganizationsAtStartup();

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getRole()).isEqualTo(OrgRole.OWNER);
        assertThat(rows.get(0).getTenantId()).isEqualTo(OTHER_ORG);
    }

    @Test
    void someoneRemovedFromAnOrganisationIsntMadeItsOwnerAgainAtTheNextRestart() {
        UserAccount owner = account(7, "owner", OTHER_ORG);
        member(owner, OTHER_ORG, OrgRole.OWNER, null);
        UserAccount removed = account(8, "removed", OTHER_ORG);
        when(users.findAll()).thenReturn(List.of(owner, removed));
        when(sessions.findByRevokedAtIsNullAndExpiresAtAfter(any())).thenReturn(List.of());

        service.applyOrganizationsAtStartup();

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getUserId()).isEqualTo(owner.getId());
    }

    @Test
    void someoneStillListedWhoWasRemovedGetsANewOrganisationRatherThanAnError() {
        UserAccount alice = account(7, "alice", OTHER_ORG);
        exists(alice);

        assertThat(service.signIn(new GitHubOAuthClient.GitHubUser(7, "alice"))).isPresent();

        assertThat(savedSession().getTenantId()).isEqualTo(NEW_TENANT);
    }

    private UserSession sessionFor(UserAccount user, UUID tenant, String scopes) {
        UserSession session = new UserSession();
        session.setUserId(user.getId());
        session.setTenantId(tenant);
        session.setScopes(scopes);
        when(users.findById(user.getId())).thenReturn(Optional.of(user));
        return session;
    }

    @Test
    void atStartupSessionsAreRevokedWhenAccessOrRoleNoLongerMatches() {
        UserAccount removed = account(7, "no-longer-listed", OTHER_ORG);
        member(removed, OTHER_ORG, OrgRole.OWNER, null);
        UserSession removedSession = sessionFor(removed, OTHER_ORG, "jobs:read,jobs:write,org:admin,org:owner");

        UserAccount invited = account(8, "invited", OTHER_ORG);
        member(invited, OTHER_ORG, OrgRole.MEMBER, UUID.randomUUID());
        UserSession keptSession = sessionFor(invited, OTHER_ORG, "jobs:read,jobs:write");

        UserAccount formerAdmin = account(9, "was-admin", Tenant.DEFAULT_ID);
        member(formerAdmin, Tenant.DEFAULT_ID, OrgRole.OWNER, null);
        UserSession formerAdminSession = sessionFor(formerAdmin, Tenant.DEFAULT_ID, "platform:admin,jobs:read,jobs:write,org:admin,org:owner");

        when(users.findAll()).thenReturn(List.of());
        when(sessions.findByRevokedAtIsNullAndExpiresAtAfter(any())).thenReturn(List.of(removedSession, keptSession, formerAdminSession));

        service.applyOrganizationsAtStartup();

        assertThat(removedSession.getRevokedAt()).isNotNull();
        assertThat(keptSession.getRevokedAt()).isNull();
        assertThat(formerAdminSession.getRevokedAt()).isNotNull();
    }

    @Test
    void signingOutRevokesTheSession() {
        UUID id = UUID.randomUUID();
        UserSession session = new UserSession();
        when(sessions.findById(id)).thenReturn(Optional.of(session));

        service.signOut(id);

        assertThat(session.getRevokedAt()).isNotNull();
    }
}
