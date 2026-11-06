package ai.gitoracle.orchestrator.auth;

import ai.gitoracle.core.security.ApiKeys;
import ai.gitoracle.core.security.SessionTokens;
import ai.gitoracle.orchestrator.repository.UserAccountRepository;
import ai.gitoracle.orchestrator.security.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClientException;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Sign in with GitHub, reached through the api-gateway.
 *
 * <p>The gateway lets {@code config}, {@code github/login} and
 * {@code github/callback} through without a credential, since a caller has none
 * yet. For {@code me} and {@code logout} it has already resolved the session and
 * sends {@code X-User-ID} / {@code X-Session-ID}, stripping any client-supplied
 * copy.
 *
 * <p>The session cookie is {@code HttpOnly}, so page scripts can't read it, and
 * {@code SameSite=Strict}. The state cookie has to be {@code Lax}, because the
 * callback is a navigation that starts on github.com. Both are {@code Secure}
 * whenever the public URL is https.
 */
// No @CrossOrigin: the API Gateway sets the CORS headers (see DashboardController).
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private static final Logger logger = LoggerFactory.getLogger(AuthController.class);

    static final String STATE_COOKIE = "gitoracle_oauth_state";
    private static final String STATE_COOKIE_PATH = "/api/v1/auth/github";
    private static final Duration STATE_TTL = Duration.ofMinutes(10);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final GitHubOAuthClient github;
    private final LoginService logins;
    private final UserAccountRepository users;
    private final String publicApiUrl;
    private final String dashboardUrl;
    private final boolean secureCookies;

    public AuthController(GitHubOAuthClient github,
                          LoginService logins,
                          UserAccountRepository users,
                          @Value("${gitoracle.auth.public-api-url:http://localhost:8080}") String publicApiUrl,
                          @Value("${gitoracle.auth.dashboard-url:http://localhost:5173}") String dashboardUrl) {
        this.github = github;
        this.logins = logins;
        this.users = users;
        this.publicApiUrl = stripTrailingSlash(publicApiUrl);
        this.dashboardUrl = stripTrailingSlash(dashboardUrl);
        this.secureCookies = this.publicApiUrl.startsWith("https://");
    }

    @GetMapping("/config")
    public Map<String, Object> config() {
        return Map.of("githubLogin", github.isConfigured());
    }

    @GetMapping("/github/login")
    public ResponseEntity<Void> login() {
        if (!github.isConfigured()) {
            return redirectToLogin("not_configured").build();
        }
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String state = HexFormat.of().formatHex(bytes);
        return redirect(github.authorizeUrl(state, callbackUrl()))
            .header(HttpHeaders.SET_COOKIE, stateCookie(state, STATE_TTL).toString())
            .build();
    }

    @GetMapping("/github/callback")
    public ResponseEntity<Void> callback(@RequestParam(required = false) String code,
                                         @RequestParam(required = false) String state,
                                         @RequestParam(required = false) String error,
                                         @CookieValue(name = STATE_COOKIE, required = false) String expectedState) {
        String clearState = stateCookie("", Duration.ZERO).toString();

        // Binds the callback to the browser that started the sign-in, so an
        // attacker can't sign a victim in to the attacker's own account.
        if (state == null || expectedState == null || !ApiKeys.constantTimeEquals(state, expectedState)) {
            logger.warn("Sign-in refused: OAuth state did not match");
            return redirectToLogin("login_failed").header(HttpHeaders.SET_COOKIE, clearState).build();
        }
        if (error != null || code == null || code.isBlank()) {
            return redirectToLogin("cancelled").header(HttpHeaders.SET_COOKIE, clearState).build();
        }

        Optional<LoginService.IssuedSession> session;
        try {
            session = logins.signIn(github.exchangeCode(code, callbackUrl()));
        } catch (GitHubOAuthClient.GitHubLoginException | RestClientException e) {
            logger.warn("Sign-in failed talking to GitHub: {}", e.getMessage());
            return redirectToLogin("login_failed").header(HttpHeaders.SET_COOKIE, clearState).build();
        }
        if (session.isEmpty()) {
            return redirectToLogin("not_invited").header(HttpHeaders.SET_COOKIE, clearState).build();
        }

        return redirect(dashboardUrl + "/")
            .header(HttpHeaders.SET_COOKIE, clearState)
            .header(HttpHeaders.SET_COOKIE, sessionCookie(session.get().token(), session.get().ttl()).toString())
            .build();
    }

    /** Who the gateway resolved this request to. A request authenticated by API key has no user. */
    @GetMapping("/me")
    public ResponseEntity<Map<String, Object>> me(@RequestHeader(name = "X-User-ID", required = false) UUID userId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tenantId", TenantContext.requireTenantId().toString());
        body.put("admin", TenantContext.isPlatformAdmin());
        if (userId == null) {
            body.put("kind", "api_key");
            return ResponseEntity.ok(body);
        }
        return users.findById(userId)
            .map(user -> {
                body.put("kind", "user");
                body.put("login", user.getGithubLogin());
                return ResponseEntity.ok(body);
            })
            .orElseGet(() -> ResponseEntity.status(HttpStatus.UNAUTHORIZED).build());
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@RequestHeader(name = "X-Session-ID", required = false) UUID sessionId) {
        if (sessionId != null) {
            logins.signOut(sessionId);
        }
        return ResponseEntity.noContent()
            .header(HttpHeaders.SET_COOKIE, sessionCookie("", Duration.ZERO).toString())
            .build();
    }

    private String callbackUrl() {
        return publicApiUrl + "/api/v1/auth/github/callback";
    }

    /** The target is always configuration, never a request parameter, so this can't be an open redirect. */
    private ResponseEntity.HeadersBuilder<?> redirectToLogin(String errorCode) {
        return redirect(dashboardUrl + "/login?error=" + errorCode);
    }

    private ResponseEntity.BodyBuilder redirect(String location) {
        return ResponseEntity.status(HttpStatus.FOUND)
            .cacheControl(CacheControl.noStore())
            .header(HttpHeaders.LOCATION, location);
    }

    private ResponseCookie sessionCookie(String value, Duration maxAge) {
        return ResponseCookie.from(SessionTokens.COOKIE_NAME, value)
            .httpOnly(true)
            .secure(secureCookies)
            .sameSite("Strict")
            .path("/")
            .maxAge(maxAge)
            .build();
    }

    private ResponseCookie stateCookie(String value, Duration maxAge) {
        return ResponseCookie.from(STATE_COOKIE, value)
            .httpOnly(true)
            .secure(secureCookies)
            .sameSite("Lax")
            .path(STATE_COOKIE_PATH)
            .maxAge(maxAge)
            .build();
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
