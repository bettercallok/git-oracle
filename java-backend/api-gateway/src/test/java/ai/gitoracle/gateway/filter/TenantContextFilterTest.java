package ai.gitoracle.gateway.filter;

import ai.gitoracle.core.security.SessionTokens;
import ai.gitoracle.gateway.security.ApiKeyAuthenticator;
import ai.gitoracle.gateway.security.SessionAuthenticator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpCookie;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class TenantContextFilterTest {

    private static final String ORIGIN = "https://app.example.com";
    private static final String TOKEN = "a".repeat(64);
    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();
    private static final UUID SESSION = UUID.randomUUID();

    private ApiKeyAuthenticator keys;
    private SessionAuthenticator sessions;
    private TenantContextFilter filter;
    private final AtomicReference<ServerWebExchange> forwarded = new AtomicReference<>();
    private final GatewayFilterChain chain = exchange -> {
        forwarded.set(exchange);
        return Mono.empty();
    };

    @BeforeEach
    void setUp() {
        keys = mock(ApiKeyAuthenticator.class);
        sessions = mock(SessionAuthenticator.class);
        when(keys.authenticate(any())).thenReturn(Mono.just(Optional.empty()));
        when(sessions.authenticate(any())).thenReturn(Mono.just(Optional.empty()));
        filter = new TenantContextFilter(keys, sessions);
        ReflectionTestUtils.setField(filter, "configuredInternalToken", "internal");
        ReflectionTestUtils.setField(filter, "allowedOrigins", "http://localhost:5173, " + ORIGIN);
    }

    private void liveSession(String... scopes) {
        when(sessions.authenticate(TOKEN)).thenReturn(Mono.just(Optional.of(
            new SessionAuthenticator.SessionPrincipal(SESSION, USER, TENANT, Set.of(scopes)))));
    }

    private MockServerWebExchange run(MockServerHttpRequest.BaseBuilder<?> request) {
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        filter.filter(exchange, chain).block();
        return exchange;
    }

    private HttpHeaders forwardedHeaders() {
        assertThat(forwarded.get()).as("request should have been forwarded").isNotNull();
        return forwarded.get().getRequest().getHeaders();
    }

    private static MockServerHttpRequest.BaseBuilder<?> withCookie(MockServerHttpRequest.BaseBuilder<?> request) {
        return request.cookie(new HttpCookie(SessionTokens.COOKIE_NAME, TOKEN));
    }

    @Test
    void aSessionCookieBecomesTenantUserAndSessionHeaders() {
        liveSession("jobs:read");

        run(withCookie(MockServerHttpRequest.get("/api/v1/jobs")));

        HttpHeaders headers = forwardedHeaders();
        assertThat(headers.getFirst("X-Tenant-ID")).isEqualTo(TENANT.toString());
        assertThat(headers.getFirst("X-Scopes")).isEqualTo("jobs:read");
        assertThat(headers.getFirst("X-User-ID")).isEqualTo(USER.toString());
        assertThat(headers.getFirst("X-Session-ID")).isEqualTo(SESSION.toString());
        assertThat(headers.getFirst("X-Internal-Token")).isEqualTo("internal");
    }

    @Test
    void theCookieDoesNotReachInternalServices() {
        liveSession("jobs:read");

        run(withCookie(MockServerHttpRequest.get("/api/v1/jobs")));

        assertThat(forwardedHeaders().containsKey(HttpHeaders.COOKIE)).isFalse();
    }

    @Test
    void clientSuppliedIdentityHeadersAreReplaced() {
        liveSession("jobs:read");
        UUID other = UUID.randomUUID();

        run(withCookie(MockServerHttpRequest.get("/api/v1/jobs")
            .header("X-Tenant-ID", other.toString())
            .header("X-User-ID", other.toString())
            .header("X-Session-ID", other.toString())
            .header("X-Scopes", "platform:admin")));

        HttpHeaders headers = forwardedHeaders();
        assertThat(headers.get("X-Tenant-ID")).containsExactly(TENANT.toString());
        assertThat(headers.get("X-User-ID")).containsExactly(USER.toString());
        assertThat(headers.get("X-Session-ID")).containsExactly(SESSION.toString());
        assertThat(headers.get("X-Scopes")).containsExactly("jobs:read");
    }

    @Test
    void anUnauthenticatedCallerCannotClaimAUserOnASignInPath() {
        run(MockServerHttpRequest.get("/api/v1/auth/config").header("X-User-ID", USER.toString()));

        assertThat(forwardedHeaders().containsKey("X-User-ID")).isFalse();
    }

    @Test
    void anInvalidSessionIsUnauthorized() {
        MockServerWebExchange exchange = run(withCookie(MockServerHttpRequest.get("/api/v1/jobs")));

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(forwarded.get()).isNull();
    }

    @Test
    void aChangeFromAnAllowedOriginIsForwarded() {
        liveSession("jobs:write");

        run(withCookie(MockServerHttpRequest.post("/api/v1/trigger").header(HttpHeaders.ORIGIN, ORIGIN)));

        forwardedHeaders();
    }

    @Test
    void aChangeFromAnotherOriginIsRefusedEvenWithALiveSession() {
        liveSession("jobs:write");

        MockServerWebExchange exchange = run(withCookie(
            MockServerHttpRequest.post("/api/v1/trigger").header(HttpHeaders.ORIGIN, "https://evil.example.com")));

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(forwarded.get()).isNull();
    }

    @Test
    void aChangeWithNoOriginIsRefused() {
        liveSession("jobs:write");

        MockServerWebExchange exchange = run(withCookie(MockServerHttpRequest.post("/api/v1/trigger")));

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void anApiKeyRequestNeedsNoOriginAndIgnoresTheCookie() {
        when(keys.authenticate("gor_key")).thenReturn(Mono.just(Optional.of(
            new ApiKeyAuthenticator.Principal(TENANT, Set.of("jobs:write"), "abc", false))));

        run(withCookie(MockServerHttpRequest.post("/api/v1/trigger").header("X-API-Key", "gor_key")));

        assertThat(forwardedHeaders().containsKey("X-User-ID")).isFalse();
        verify(sessions, never()).authenticate(any());
    }

    @Test
    void anOrdinarySessionCannotReachAdminPaths() {
        liveSession("jobs:read", "jobs:write");

        MockServerWebExchange exchange = run(withCookie(MockServerHttpRequest.get("/api/v1/admin/tenants")));

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(forwarded.get()).isNull();
    }

    @Test
    void anAdminSessionCanReachAdminPaths() {
        liveSession("platform:admin");

        run(withCookie(MockServerHttpRequest.get("/api/v1/admin/tenants")));

        forwardedHeaders();
    }

    @Test
    void signInPathsNeedNoCredentialAndKeepTheStateCookie() {
        run(MockServerHttpRequest.get("/api/v1/auth/github/callback?code=c&state=s")
            .header(HttpHeaders.COOKIE, "gitoracle_oauth_state=s"));

        assertThat(forwardedHeaders().getFirst(HttpHeaders.COOKIE)).isEqualTo("gitoracle_oauth_state=s");
    }

    @Test
    void theRestOfTheAuthPathsStillNeedACredential() {
        for (String path : new String[] {"/api/v1/auth/me", "/api/v1/auth/github/login/x", "/api/v1/auth/config/../../jobs"}) {
            forwarded.set(null);
            MockServerWebExchange exchange = run(MockServerHttpRequest.get(path));
            assertThat(exchange.getResponse().getStatusCode()).as(path).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(forwarded.get()).as(path).isNull();
        }
    }

    @Test
    void signingOutEvictsTheCachedSession() {
        liveSession("jobs:read");

        run(withCookie(MockServerHttpRequest.post("/api/v1/auth/logout").header(HttpHeaders.ORIGIN, ORIGIN)));

        verify(sessions).evict(TOKEN);
    }

    @Test
    void otherRequestsDoNotEvict() {
        liveSession("jobs:read");

        run(withCookie(MockServerHttpRequest.get("/api/v1/jobs")));

        verify(sessions, never()).evict(any());
    }

    @Test
    void aPlaceholderInternalTokenRefusesToStart() {
        ReflectionTestUtils.setField(filter, "configuredInternalToken", "change_me_internal_service_token");

        org.assertj.core.api.Assertions.assertThatThrownBy(filter::requireInternalTokenConfigured)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("placeholder");
    }

    private void keyWithScopes(String... scopes) {
        when(keys.authenticate("gor_key")).thenReturn(Mono.just(Optional.of(
            new ApiKeyAuthenticator.Principal(TENANT, Set.of(scopes), "abc", false))));
    }

    private MockServerWebExchange callWithKey(MockServerHttpRequest.BaseBuilder<?> request) {
        forwarded.set(null);
        return run(request.header("X-API-Key", "gor_key"));
    }

    @Test
    void aReadOnlyKeyCanReadButNotChangeAnything() {
        keyWithScopes("jobs:read");

        callWithKey(MockServerHttpRequest.get("/api/v1/jobs"));
        forwardedHeaders();

        MockServerWebExchange write = callWithKey(MockServerHttpRequest.post("/api/v1/trigger"));
        assertThat(write.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(forwarded.get()).isNull();
    }

    @Test
    void aKeyWithNoScopesCanDoNothing() {
        keyWithScopes();

        assertThat(callWithKey(MockServerHttpRequest.get("/api/v1/jobs")).getResponse().getStatusCode())
            .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(forwarded.get()).isNull();
    }

    @Test
    void anApiKeyCannotManageKeysEvenAsAdmin() {
        keyWithScopes("platform:admin", "jobs:read", "jobs:write");

        assertThat(callWithKey(MockServerHttpRequest.get("/api/v1/keys")).getResponse().getStatusCode())
            .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void aSessionCanManageKeys() {
        liveSession("jobs:read", "jobs:write");

        run(withCookie(MockServerHttpRequest.get("/api/v1/keys")));

        forwardedHeaders();
    }

    @Test
    void internalRoutesArentReachableThroughTheGatewayEvenForAnAdmin() {
        liveSession("platform:admin", "jobs:read", "jobs:write");

        for (String path : new String[] {"/api/v1/jobs/1/events", "/api/v1/jobs/1/prompt-version", "/api/v1/budget/1/record"}) {
            forwarded.set(null);
            MockServerWebExchange exchange = run(withCookie(MockServerHttpRequest.post(path).header(HttpHeaders.ORIGIN, ORIGIN)));
            assertThat(exchange.getResponse().getStatusCode()).as(path).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(forwarded.get()).as(path).isNull();
        }
    }

    @Test
    void anEncodedAdminPathIsStillAdmin() {
        keyWithScopes("jobs:read", "jobs:write");

        assertThat(callWithKey(MockServerHttpRequest.method(org.springframework.http.HttpMethod.GET, java.net.URI.create("/api/v1/%61dmin/tenants")))
            .getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(forwarded.get()).isNull();
    }

    @Test
    void switchingOrganisationEvictsTheCachedSession() {
        liveSession("jobs:read");

        run(withCookie(MockServerHttpRequest.post("/api/v1/auth/switch-org").header(HttpHeaders.ORIGIN, ORIGIN)));

        verify(sessions).evict(TOKEN);
    }

    @Test
    void aViewerCantChangeAnythingOrManageTheOrganisation() {
        liveSession("jobs:read");

        for (String path : new String[] {"/api/v1/trigger", "/api/v1/org/invitations", "/api/v1/keys"}) {
            forwarded.set(null);
            MockServerWebExchange exchange = run(withCookie(MockServerHttpRequest.post(path).header(HttpHeaders.ORIGIN, ORIGIN)));
            assertThat(exchange.getResponse().getStatusCode()).as(path).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(forwarded.get()).as(path).isNull();
        }
    }

    @Test
    void anOrgAdminCanManageTheOrganisationButNotThePlatform() {
        liveSession("jobs:read", "jobs:write", "org:admin");

        run(withCookie(MockServerHttpRequest.post("/api/v1/org/invitations").header(HttpHeaders.ORIGIN, ORIGIN)));
        forwardedHeaders();

        forwarded.set(null);
        MockServerWebExchange exchange = run(withCookie(MockServerHttpRequest.get("/api/v1/admin/repos")));
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(forwarded.get()).isNull();
    }
}
