package ai.gitoracle.orchestrator.auth;

import ai.gitoracle.core.model.postgres.UserAccount;
import ai.gitoracle.orchestrator.repository.UserAccountRepository;
import ai.gitoracle.orchestrator.security.TenantContextTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.ResourceAccessException;

import java.time.Duration;
import java.util.List;
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
        return new AuthController(github, logins, new LoginPolicy("alice", ""), users, publicApiUrl, DASHBOARD + "/");
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
        AuthController nobodyInvited = new AuthController(github, logins, new LoginPolicy("", ""), users,
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
}
