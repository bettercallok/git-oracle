package ai.gitoracle.orchestrator.auth;

import ai.gitoracle.core.model.postgres.Tenant;
import ai.gitoracle.core.model.postgres.UserAccount;
import ai.gitoracle.core.model.postgres.UserSession;
import ai.gitoracle.core.security.SessionTokens;
import ai.gitoracle.orchestrator.repository.UserAccountRepository;
import ai.gitoracle.orchestrator.repository.UserSessionRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class LoginServiceTest {

    private static final long ADMIN_ID = 42;
    private static final UUID NEW_TENANT = UUID.randomUUID();

    private UserAccountRepository users;
    private UserSessionRepository sessions;
    private EntityManager entityManager;
    private LoginService service;

    @BeforeEach
    void setUp() {
        users = mock(UserAccountRepository.class);
        sessions = mock(UserSessionRepository.class);
        entityManager = mock(EntityManager.class);
        when(users.findByGithubId(any())).thenReturn(Optional.empty());
        when(users.save(any())).thenAnswer(call -> {
            UserAccount user = call.getArgument(0);
            if (user.getId() == null) user.setId(UUID.randomUUID());
            return user;
        });
        doAnswer(call -> {
            ((Tenant) call.getArgument(0)).setId(NEW_TENANT);
            return null;
        }).when(entityManager).persist(any(Tenant.class));
        service = new LoginService(new LoginPolicy("alice", String.valueOf(ADMIN_ID)), users, sessions, entityManager, Duration.ofDays(7));
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

    @Test
    void someoneNotInvitedGetsNoUserTenantOrSession() {
        assertThat(service.signIn(new GitHubOAuthClient.GitHubUser(7, "mallory"))).isEmpty();

        verify(users, never()).save(any());
        verify(sessions, never()).save(any());
        verify(entityManager, never()).persist(any());
    }

    @Test
    void anInviteeGetsTheirOwnTenantAndNoAdminScope() {
        Optional<LoginService.IssuedSession> issued = service.signIn(new GitHubOAuthClient.GitHubUser(7, "alice"));

        UserSession session = savedSession();
        assertThat(session.getTenantId()).isEqualTo(NEW_TENANT);
        assertThat(session.getScopes()).doesNotContain("platform:admin");
        assertThat(issued).isPresent();
    }

    @Test
    void onlyTheTokensHashIsStored() {
        LoginService.IssuedSession issued = service.signIn(new GitHubOAuthClient.GitHubUser(7, "alice")).orElseThrow();

        UserSession session = savedSession();
        assertThat(session.getTokenHash()).isEqualTo(SessionTokens.hash(issued.token())).isNotEqualTo(issued.token());
    }

    @Test
    void theSessionExpiresAfterItsTtl() {
        service.signIn(new GitHubOAuthClient.GitHubUser(7, "alice"));

        assertThat(savedSession().getExpiresAt()).isBetween(
            OffsetDateTime.now().plusDays(7).minusMinutes(1), OffsetDateTime.now().plusDays(7).plusMinutes(1));
    }

    @Test
    void aReturningInviteeKeepsTheirTenantAndTheirLoginIsRefreshed() {
        UUID existing = UUID.randomUUID();
        UserAccount user = account(7, "old-name", existing);
        when(users.findByGithubId(7L)).thenReturn(Optional.of(user));
        LoginService renamed = new LoginService(new LoginPolicy("new-name", ""), users, sessions, entityManager, Duration.ofDays(7));

        renamed.signIn(new GitHubOAuthClient.GitHubUser(7, "new-name"));

        assertThat(user.getTenantId()).isEqualTo(existing);
        assertThat(user.getGithubLogin()).isEqualTo("new-name");
        verify(entityManager, never()).persist(any());
    }

    @Test
    void anAdminWorksInTheDefaultTenantWithAdminScope() {
        service.signIn(new GitHubOAuthClient.GitHubUser(ADMIN_ID, "operator"));

        UserSession session = savedSession();
        assertThat(session.getTenantId()).isEqualTo(Tenant.DEFAULT_ID);
        assertThat(session.getScopes()).contains("platform:admin");
    }

    @Test
    void aFormerAdminLeavesTheDefaultTenant() {
        UserAccount user = account(7, "alice", Tenant.DEFAULT_ID);
        when(users.findByGithubId(7L)).thenReturn(Optional.of(user));

        service.signIn(new GitHubOAuthClient.GitHubUser(7, "alice"));

        assertThat(user.getTenantId()).isEqualTo(NEW_TENANT);
    }

    private UserSession activeSessionFor(UserAccount user, String scopes) {
        UserSession session = new UserSession();
        session.setUserId(user.getId());
        session.setScopes(scopes);
        when(users.findById(user.getId())).thenReturn(Optional.of(user));
        return session;
    }

    @Test
    void atStartupSessionsOfRemovedUsersAreRevoked() {
        UserSession removed = activeSessionFor(account(7, "no-longer-invited", NEW_TENANT), "jobs:read,jobs:write");
        UserSession kept = activeSessionFor(account(8, "alice", NEW_TENANT), "jobs:read,jobs:write");
        when(sessions.findByRevokedAtIsNullAndExpiresAtAfter(any())).thenReturn(List.of(removed, kept));

        service.revokeSessionsNoLongerAllowed();

        assertThat(removed.getRevokedAt()).isNotNull();
        assertThat(kept.getRevokedAt()).isNull();
    }

    @Test
    void atStartupAFormerAdminsAdminSessionIsRevokedEvenIfStillInvited() {
        UserSession session = activeSessionFor(account(8, "alice", NEW_TENANT), "platform:admin,jobs:read,jobs:write");
        when(sessions.findByRevokedAtIsNullAndExpiresAtAfter(any())).thenReturn(List.of(session));

        service.revokeSessionsNoLongerAllowed();

        assertThat(session.getRevokedAt()).isNotNull();
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
