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
import ai.gitoracle.orchestrator.service.OrganizationService.Actor;
import ai.gitoracle.orchestrator.service.OrganizationService.OrganizationException;
import jakarta.persistence.EntityManager;
import org.assertj.core.api.ThrowableAssert;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class OrganizationServiceTest {

    private static final UUID ORG = UUID.randomUUID();

    private OrgMembershipRepository memberships;
    private OrgInvitationRepository invitations;
    private UserAccountRepository users;
    private UserSessionRepository sessions;
    private ApiKeyRepository apiKeys;
    private GitHubOAuthClient github;
    private AuditLog audit;
    private OrganizationService service;
    private final List<OrgMembership> rows = new ArrayList<>();

    @BeforeEach
    void setUp() {
        memberships = mock(OrgMembershipRepository.class);
        invitations = mock(OrgInvitationRepository.class);
        users = mock(UserAccountRepository.class);
        sessions = mock(UserSessionRepository.class);
        apiKeys = mock(ApiKeyRepository.class);
        github = mock(GitHubOAuthClient.class);
        audit = mock(AuditLog.class);
        EntityManager entityManager = mock(EntityManager.class);
        when(entityManager.find(eq(Tenant.class), any())).thenReturn(new Tenant());
        when(memberships.findByTenantIdAndUserId(any(), any())).thenAnswer(call -> rows.stream()
            .filter(row -> row.getTenantId().equals(call.getArgument(0)) && row.getUserId().equals(call.getArgument(1))).findFirst());
        when(memberships.lockByTenantAndRole(any(), any())).thenAnswer(call -> rows.stream()
            .filter(row -> row.getTenantId().equals(call.getArgument(0)) && row.getRole() == call.getArgument(1)).toList());
        when(users.findById(any())).thenReturn(Optional.empty());
        when(users.findByGithubId(any())).thenReturn(Optional.empty());
        when(invitations.save(any())).thenAnswer(call -> call.getArgument(0));
        when(github.lookupUser(any())).thenAnswer(call -> Optional.of(new GitHubOAuthClient.GitHubUser(77, call.getArgument(0))));
        service = new OrganizationService(memberships, invitations, users, sessions, apiKeys, github, audit, entityManager);
    }

    private UUID memberWith(OrgRole role) {
        UUID user = UUID.randomUUID();
        OrgMembership row = new OrgMembership();
        row.setTenantId(ORG);
        row.setUserId(user);
        row.setRole(role);
        rows.add(row);
        return user;
    }

    private OrgRole roleOf(UUID user) {
        return rows.stream().filter(row -> row.getUserId().equals(user)).findFirst().orElseThrow().getRole();
    }

    private static Actor actor(UUID user) {
        return new Actor(user, false);
    }

    private static void refused(ThrowableAssert.ThrowingCallable call, HttpStatus status) {
        assertThatThrownBy(call).isInstanceOfSatisfying(OrganizationException.class,
            e -> assertThat(e.status()).isEqualTo(status));
    }

    @Test
    void anAdminCanInviteUpToTheirOwnRoleOnly() {
        UUID admin = memberWith(OrgRole.ADMIN);

        OrgInvitation invitation = service.invite(ORG, actor(admin), "alice", OrgRole.ADMIN);

        assertThat(invitation.getGithubId()).isEqualTo(77);
        assertThat(invitation.getRole()).isEqualTo(OrgRole.ADMIN);
        verify(audit).record(ORG, "invitation.created", "alice", "role=ADMIN");
        refused(() -> service.invite(ORG, actor(admin), "bob", OrgRole.OWNER), HttpStatus.FORBIDDEN);
    }

    @Test
    void aMemberCantInviteAnyone() {
        UUID member = memberWith(OrgRole.MEMBER);

        refused(() -> service.invite(ORG, actor(member), "alice", OrgRole.VIEWER), HttpStatus.FORBIDDEN);
        verify(invitations, never()).save(any());
    }

    @Test
    void someoneFromAnotherOrganisationCantInvite() {
        refused(() -> service.invite(ORG, actor(UUID.randomUUID()), "alice", OrgRole.VIEWER), HttpStatus.FORBIDDEN);
    }

    @Test
    void anUnknownGitHubAccountIsAClearError() {
        UUID owner = memberWith(OrgRole.OWNER);
        when(github.lookupUser("ghost")).thenReturn(Optional.empty());

        refused(() -> service.invite(ORG, actor(owner), "ghost", OrgRole.VIEWER), HttpStatus.BAD_REQUEST);
    }

    @Test
    void someoneAlreadyInTheOrganisationCantBeInvited() {
        UUID owner = memberWith(OrgRole.OWNER);
        UUID existing = memberWith(OrgRole.VIEWER);
        UserAccount account = new UserAccount();
        account.setId(existing);
        when(users.findByGithubId(77L)).thenReturn(Optional.of(account));

        refused(() -> service.invite(ORG, actor(owner), "alice", OrgRole.VIEWER), HttpStatus.CONFLICT);
    }

    @Test
    void aNewInvitationReplacesAnEarlierOne() {
        UUID owner = memberWith(OrgRole.OWNER);
        OrgInvitation earlier = new OrgInvitation();
        when(invitations.findByTenantIdAndGithubIdAndAcceptedAtIsNullAndRevokedAtIsNull(ORG, 77L)).thenReturn(List.of(earlier));

        service.invite(ORG, actor(owner), "alice", OrgRole.VIEWER);

        assertThat(earlier.getRevokedAt()).isNotNull();
    }

    @Test
    void anAdminCantRevokeAnInvitationAboveTheirRole() {
        UUID admin = memberWith(OrgRole.ADMIN);
        OrgInvitation invitation = new OrgInvitation();
        invitation.setTenantId(ORG);
        invitation.setRole(OrgRole.OWNER);
        UUID id = UUID.randomUUID();
        when(invitations.findById(id)).thenReturn(Optional.of(invitation));

        refused(() -> service.revokeInvitation(ORG, actor(admin), id), HttpStatus.FORBIDDEN);
    }

    @Test
    void anotherOrganisationsInvitationLooksLikeItDoesntExist() {
        UUID owner = memberWith(OrgRole.OWNER);
        OrgInvitation theirs = new OrgInvitation();
        theirs.setTenantId(UUID.randomUUID());
        UUID id = UUID.randomUUID();
        when(invitations.findById(id)).thenReturn(Optional.of(theirs));

        refused(() -> service.revokeInvitation(ORG, actor(owner), id), HttpStatus.NOT_FOUND);
        assertThat(theirs.getRevokedAt()).isNull();
    }

    @Test
    void aRoleChangeEndsTheirSessionsAndIsAudited() {
        UUID owner = memberWith(OrgRole.OWNER);
        UUID member = memberWith(OrgRole.MEMBER);
        UserSession session = new UserSession();
        when(sessions.findByUserIdAndTenantIdAndRevokedAtIsNull(member, ORG)).thenReturn(List.of(session));

        service.changeRole(ORG, actor(owner), member, OrgRole.ADMIN);

        assertThat(roleOf(member)).isEqualTo(OrgRole.ADMIN);
        assertThat(session.getRevokedAt()).isNotNull();
        verify(audit).record(eq(ORG), eq("member.role_changed"), any(), eq("MEMBER -> ADMIN"));
        verify(apiKeys, never()).findByTenantIdAndCreatedByUserIdAndRevokedAtIsNull(any(), any());
    }

    @Test
    void becomingAViewerRevokesTheKeysTheyMade() {
        UUID owner = memberWith(OrgRole.OWNER);
        UUID member = memberWith(OrgRole.MEMBER);
        ApiKey key = new ApiKey();
        when(apiKeys.findByTenantIdAndCreatedByUserIdAndRevokedAtIsNull(ORG, member)).thenReturn(List.of(key));

        service.changeRole(ORG, actor(owner), member, OrgRole.VIEWER);

        assertThat(key.getRevokedAt()).isNotNull();
    }

    @Test
    void anAdminCantPromoteAboveThemselvesOrChangeAnOwner() {
        UUID admin = memberWith(OrgRole.ADMIN);
        UUID member = memberWith(OrgRole.MEMBER);
        UUID owner = memberWith(OrgRole.OWNER);
        memberWith(OrgRole.OWNER);

        refused(() -> service.changeRole(ORG, actor(admin), member, OrgRole.OWNER), HttpStatus.FORBIDDEN);
        refused(() -> service.changeRole(ORG, actor(admin), owner, OrgRole.VIEWER), HttpStatus.FORBIDDEN);
        refused(() -> service.removeMember(ORG, actor(admin), owner), HttpStatus.FORBIDDEN);
        assertThat(roleOf(owner)).isEqualTo(OrgRole.OWNER);
    }

    @Test
    void theLastOwnerCantBeDemotedOrRemoved() {
        UUID owner = memberWith(OrgRole.OWNER);

        refused(() -> service.changeRole(ORG, actor(owner), owner, OrgRole.MEMBER), HttpStatus.CONFLICT);
        refused(() -> service.removeMember(ORG, actor(owner), owner), HttpStatus.CONFLICT);
        assertThat(roleOf(owner)).isEqualTo(OrgRole.OWNER);
        verify(memberships, never()).delete(any());
    }

    @Test
    void anOwnerCanLeaveOnceThereIsAnother() {
        UUID owner = memberWith(OrgRole.OWNER);
        memberWith(OrgRole.OWNER);

        service.removeMember(ORG, actor(owner), owner);

        verify(memberships).delete(any());
    }

    @Test
    void removingSomeoneEndsTheirSessionsAndRevokesTheirKeys() {
        UUID owner = memberWith(OrgRole.OWNER);
        UUID member = memberWith(OrgRole.MEMBER);
        UserSession session = new UserSession();
        ApiKey key = new ApiKey();
        when(sessions.findByUserIdAndTenantIdAndRevokedAtIsNull(member, ORG)).thenReturn(List.of(session));
        when(apiKeys.findByTenantIdAndCreatedByUserIdAndRevokedAtIsNull(ORG, member)).thenReturn(List.of(key));

        service.removeMember(ORG, actor(owner), member);

        verify(memberships).delete(any());
        assertThat(session.getRevokedAt()).isNotNull();
        assertThat(key.getRevokedAt()).isNotNull();
        verify(audit).record(eq(ORG), eq("member.removed"), any(), eq("was MEMBER"));
    }

    @Test
    void aPlatformAdminCanActWithoutBeingAMember() {
        UUID member = memberWith(OrgRole.OWNER);
        memberWith(OrgRole.OWNER);

        service.changeRole(ORG, new Actor(UUID.randomUUID(), true), member, OrgRole.VIEWER);

        assertThat(roleOf(member)).isEqualTo(OrgRole.VIEWER);
    }

    @Test
    void aMissingMemberIsNotFound() {
        UUID owner = memberWith(OrgRole.OWNER);

        refused(() -> service.removeMember(ORG, actor(owner), UUID.randomUUID()), HttpStatus.NOT_FOUND);
    }

    @Test
    @SuppressWarnings("unchecked")
    void renamingNeedsAnAdminAndAFreeNameAndIsAudited() {
        EntityManager entityManager = mock(EntityManager.class);
        Tenant tenant = new Tenant();
        tenant.setOrgName("old");
        when(entityManager.find(eq(Tenant.class), any())).thenReturn(tenant);
        jakarta.persistence.TypedQuery<Long> query = mock(jakarta.persistence.TypedQuery.class);
        when(entityManager.createQuery(any(String.class), eq(Long.class))).thenReturn(query);
        when(query.setParameter(any(String.class), any())).thenReturn(query);
        when(query.getSingleResult()).thenReturn(0L, 1L);
        OrganizationService renaming = new OrganizationService(memberships, invitations, users, sessions, apiKeys, github, audit, entityManager);
        UUID admin = memberWith(OrgRole.ADMIN);
        UUID member = memberWith(OrgRole.MEMBER);

        refused(() -> renaming.rename(ORG, actor(member), "new"), HttpStatus.FORBIDDEN);

        renaming.rename(ORG, actor(admin), "new");
        assertThat(tenant.getOrgName()).isEqualTo("new");
        verify(audit).record(ORG, "organization.renamed", "new", "was old");

        refused(() -> renaming.rename(ORG, actor(admin), "taken"), HttpStatus.CONFLICT);
        assertThat(tenant.getOrgName()).isEqualTo("new");
    }
}
