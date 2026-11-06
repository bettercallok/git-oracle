package ai.gitoracle.orchestrator.auth;

import ai.gitoracle.core.model.postgres.OrgMembership;
import ai.gitoracle.core.model.postgres.OrgRole;
import ai.gitoracle.core.model.postgres.UserAccount;
import ai.gitoracle.orchestrator.dto.Requests;
import ai.gitoracle.orchestrator.repository.UserAccountRepository;
import ai.gitoracle.orchestrator.security.TenantContextTestSupport;
import ai.gitoracle.orchestrator.service.GitHubConnectService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.ResourceAccessException;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class AuthControllerTest {

    private static final String DASHBOARD = "https://app.example.com";

    private GitHubOAuthClient github;
    private LoginService logins;
    private UserAccountRepository users;
    private final EntityManager entityManager = mock(EntityManager.class);
    private final GitHubConnectService connects = mock(GitHubConnectService.class);
    private AuthController controller;

    @BeforeEach
    void setUp() {
        github = mock(GitHubOAuthClient.class);
        logins = mock(LoginService.class);
        users = mock(UserAccountRepository.class);
        when(github.isConfigured()).thenReturn(true);
        when(github.authorizeUrl(anyString(), anyString())).thenReturn("https://github.com/authorize");
        controller = controllerFor("https://api.example.com/");
    }

    private AuthController controllerFor(String publicApiUrl) {
        return new AuthController(github, logins, new LoginPolicy("alice", ""), connects, users, entityManager, publicApiUrl, DASHBOARD + "/");
    }

    private static String location(ResponseEntity<?> response) {
        return response.getHeaders().getFirst(HttpHeaders.LOCATION);
    }

    private static List<String> cookies(ResponseEntity<?> response) {
        return response.getHeaders().get(HttpHeaders.SET_COOKIE);
    }

    private static String sessionCookie(ResponseEntity<?> response) {
        return cookies(response).stream().filter(c -> c.startsWith("gitoracle_session=")).findFirst().orElseThrow();
    }

    private void gitHubReturnsInvitedUser() {
        GitHubOAuthClient.GitHubUser user = new GitHubOAuthClient.GitHubUser(7, "alice");
        when(github.exchangeCode(anyString(), anyString())).thenReturn(user);
        when(logins.signIn(user)).thenReturn(Optional.of(new LoginService.IssuedSession("the-token", Duration.ofDays(7))));
    }

    @Test
    void loginRedirectsToGitHubWithAFreshStateInACookie() {
        ResponseEntity<Void> response = controller.login();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FOUND);
        assertThat(location(response)).isEqualTo("https://github.com/authorize");
        String cookie = cookies(response).get(0);
        assertThat(cookie).startsWith("gitoracle_oauth_state=").contains("HttpOnly").contains("SameSite=Lax").contains("Secure");
        String state = cookie.substring("gitoracle_oauth_state=".length(), cookie.indexOf(';'));
        verify(github).authorizeUrl(state, "https://api.example.com/api/v1/auth/github/callback");
    }

    @Test
    void loginWithoutGitHubConfiguredReturnsToTheLoginPage() {
        when(github.isConfigured()).thenReturn(false);

        assertThat(location(controller.login())).isEqualTo(DASHBOARD + "/login?error=not_configured");
    }

    @Test
    void aSuccessfulCallbackSetsAHardenedSessionCookieAndClearsTheState() {
        gitHubReturnsInvitedUser();

        ResponseEntity<Void> response = controller.callback("code", "st", null, "st");

        assertThat(location(response)).isEqualTo(DASHBOARD + "/");
        assertThat(sessionCookie(response)).startsWith("gitoracle_session=the-token")
            .contains("HttpOnly").contains("Secure").contains("SameSite=Strict").contains("Path=/");
        assertThat(cookies(response)).anyMatch(c -> c.startsWith("gitoracle_oauth_state=;") && c.contains("Max-Age=0"));
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
    }

    @Test
    void cookiesAreNotSecureOnPlainHttpLocalhost() {
        gitHubReturnsInvitedUser();

        ResponseEntity<Void> response = controllerFor("http://localhost:8080").callback("code", "st", null, "st");

        assertThat(sessionCookie(response)).doesNotContain("Secure");
    }

    @Test
    void aMismatchedOrMissingStateIsRefusedWithoutTouchingGitHub() {
        assertThat(location(controller.callback("code", "attacker", null, "st"))).endsWith("/login?error=login_failed");
        assertThat(location(controller.callback("code", "st", null, null))).endsWith("/login?error=login_failed");
        assertThat(location(controller.callback("code", null, null, "st"))).endsWith("/login?error=login_failed");

        verify(github, never()).exchangeCode(any(), any());
    }

    @Test
    void cancellingOnGitHubIsReportedAsThat() {
        assertThat(location(controller.callback(null, "st", "access_denied", "st"))).endsWith("/login?error=cancelled");
    }

    @Test
    void someoneNotInvitedGetsNoSessionCookie() {
        GitHubOAuthClient.GitHubUser user = new GitHubOAuthClient.GitHubUser(7, "mallory");
        when(github.exchangeCode(anyString(), anyString())).thenReturn(user);
        when(logins.signIn(user)).thenReturn(Optional.empty());

        ResponseEntity<Void> response = controller.callback("code", "st", null, "st");

        assertThat(location(response)).endsWith("/login?error=not_invited");
        assertThat(cookies(response)).noneMatch(c -> c.startsWith("gitoracle_session="));
    }

    @Test
    void gitHubFailuresReturnToTheLoginPage() {
        when(github.exchangeCode(anyString(), anyString()))
            .thenThrow(new GitHubOAuthClient.GitHubLoginException("refused"))
            .thenThrow(new ResourceAccessException("timeout"));

        assertThat(location(controller.callback("code", "st", null, "st"))).endsWith("/login?error=login_failed");
        assertThat(location(controller.callback("code", "st", null, "st"))).endsWith("/login?error=login_failed");
    }

    @Test
    void logoutRevokesTheSessionAndClearsTheCookie() {
        UUID session = UUID.randomUUID();

        ResponseEntity<Void> response = controller.logout(session);

        verify(logins).signOut(session);
        assertThat(sessionCookie(response)).contains("Max-Age=0");
    }

    @Test
    void logoutWithAnApiKeyHasNothingToRevoke() {
        assertThat(controller.logout(null).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verifyNoInteractions(logins);
    }

    @Test
    void configReportsWhetherGitHubSignInIsAvailable() {
        assertThat(controller.config()).containsEntry("githubLogin", true);
    }

    @Test
    void configSaysNoWithoutAppCredentials() {
        when(github.isConfigured()).thenReturn(false);

        assertThat(controller.config()).containsEntry("githubLogin", false);
    }

    @Test
    void configSaysNoWhenNobodyIsInvitedSoAFreshForkKeepsItsKeyForm() {
        AuthController nobodyInvited = new AuthController(github, logins, new LoginPolicy("", ""), connects, users, entityManager,
            "http://localhost:8080", DASHBOARD);

        assertThat(nobodyInvited.config()).containsEntry("githubLogin", false);
    }

    private ResponseEntity<java.util.Map<String, Object>> meAs(UUID userId, boolean admin) {
        Set<String> scopes = admin ? Set.of("platform:admin") : Set.of("jobs:read");
        return TenantContextTestSupport.runAs(UUID.randomUUID(), scopes, () -> controller.me(userId));
    }

    @Test
    void meDescribesASignedInUser() {
        UUID id = UUID.randomUUID();
        UserAccount user = new UserAccount();
        user.setGithubLogin("alice");
        when(users.findById(id)).thenReturn(Optional.of(user));

        assertThat(meAs(id, false).getBody())
            .containsEntry("kind", "user").containsEntry("login", "alice").containsEntry("admin", false);
    }

    @Test
    void meForAnApiKeyHasNoUser() {
        assertThat(meAs(null, true).getBody()).containsEntry("kind", "api_key").doesNotContainKey("login");
        verifyNoInteractions(users);
    }

    @Test
    void meForADeletedUserIsUnauthorized() {
        when(users.findById(any())).thenReturn(Optional.empty());

        assertThat(meAs(UUID.randomUUID(), false).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void meListsThePersonsOrganisationsAndTheirRoleInTheActiveOne() {
        UUID id = UUID.randomUUID();
        UUID active = UUID.randomUUID();
        UserAccount user = new UserAccount();
        user.setGithubLogin("alice");
        when(users.findById(id)).thenReturn(Optional.of(user));
        OrgMembership membership = new OrgMembership();
        membership.setTenantId(active);
        membership.setRole(OrgRole.ADMIN);
        when(logins.organizationsOf(id)).thenReturn(List.of(membership));

        Map<String, Object> body = TenantContextTestSupport.runAs(active, Set.of("jobs:read"), () -> controller.me(id)).getBody();

        assertThat(body).containsEntry("role", OrgRole.ADMIN);
        assertThat((List<?>) body.get("organizations")).hasSize(1);
    }

    @Test
    void switchingNeedsASignedInPersonWhoBelongsToTheOrganisation() {
        UUID session = UUID.randomUUID();
        UUID tenant = UUID.randomUUID();
        when(logins.switchOrganization(session, tenant)).thenReturn(false);

        assertThat(controller.switchOrganization(null, new Requests.SwitchOrganization(tenant)).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(controller.switchOrganization(session, new Requests.SwitchOrganization(tenant)).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        when(logins.switchOrganization(session, tenant)).thenReturn(true);
        assertThat(controller.switchOrganization(session, new Requests.SwitchOrganization(tenant)).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void anInstallationRedirectGoesOnToSignInWithItsStateInACookie() {
        when(connects.recordInstallation("st", "55", "install")).thenReturn(Optional.empty());

        ResponseEntity<Void> response = controller.setup("st", "55", "install");

        assertThat(location(response)).isEqualTo("https://github.com/authorize");
        assertThat(cookies(response).get(0)).startsWith("gitoracle_oauth_state=st");
        verify(github).authorizeUrl("st", "https://api.example.com/api/v1/auth/github/callback");
    }

    @Test
    void anInvalidInstallationRedirectEndsOnTheRepositoriesPage() {
        when(connects.recordInstallation(any(), any(), any())).thenReturn(Optional.of(GitHubConnectService.Outcome.FAILED));

        assertThat(location(controller.setup("nope", "55", "install"))).isEqualTo(DASHBOARD + "/repos?github=failed");
    }

    @Test
    void aCallbackForAConnectionConnectsRatherThanSigningIn() {
        when(connects.isConnecting("st")).thenReturn(true);
        var proof = new GitHubOAuthClient.UserInstallations(new GitHubOAuthClient.GitHubUser(7, "alice"), Set.of(55L));
        when(github.exchangeCodeForInstallations(anyString(), anyString())).thenReturn(proof);
        when(connects.complete("st", proof)).thenReturn(GitHubConnectService.Outcome.CONNECTED);

        ResponseEntity<Void> response = controller.callback("code", "st", null, "st");

        assertThat(location(response)).isEqualTo(DASHBOARD + "/repos?github=connected");
        assertThat(cookies(response)).noneMatch(c -> c.startsWith("gitoracle_session="));
        verify(logins, never()).signIn(any());
    }

    @Test
    void aConnectionStillNeedsTheStateCookieFromTheSameBrowser() {
        when(connects.isConnecting(any())).thenReturn(true);

        assertThat(location(controller.callback("code", "st", null, "other"))).endsWith("/login?error=login_failed");

        verify(connects, never()).complete(any(), any());
    }
}
