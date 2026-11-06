package ai.gitoracle.gateway.filter;

import ai.gitoracle.core.model.postgres.ApiKey;
import ai.gitoracle.core.security.RequestPaths;
import ai.gitoracle.core.security.RouteAccess;
import ai.gitoracle.core.security.SessionTokens;
import ai.gitoracle.gateway.security.ApiKeyAuthenticator;
import ai.gitoracle.gateway.security.SessionAuthenticator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpCookie;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.Arrays;
import java.util.Set;
import java.util.UUID;

/**
 * Global filter that authenticates every inbound API request and establishes the
 * tenant context the rest of the system runs under.
 *
 * <h2>What changed, and why it mattered</h2>
 * This filter used to read {@code X-Tenant-ID} straight off the incoming request
 * and forward it, defaulting to the zero UUID when absent. Combined with a
 * single shared {@code X-API-Key} valid for the whole installation, that meant
 * <em>any</em> authenticated caller could read or mutate <em>any</em> tenant's
 * data by changing one header — tenancy existed in the schema and in the code's
 * vocabulary, but was not enforced anywhere.
 *
 * Now:
 * <ul>
 *   <li>The tenant is <b>derived from the API key</b> ({@link ApiKeyAuthenticator}),
 *       never read from the request.</li>
 *   <li>Any client-supplied {@code X-Tenant-ID} or {@code X-Scopes} header is
 *       <b>stripped</b> before routing, so a caller cannot hand itself an
 *       identity by setting the header the downstream services trust.</li>
 *   <li>{@link RouteAccess} decides what each route requires: read, write or admin
 *       scope, a signed-in person, or nothing through the gateway at all.</li>
 * </ul>
 *
 * <h2>Browsers: the session cookie</h2>
 * A request with no {@code X-API-Key} may instead carry the session cookie set by
 * Sign in with GitHub ({@link SessionAuthenticator}); it resolves to the same
 * tenant and scope headers, so nothing downstream needs to care which was used.
 * Browsers attach cookies by themselves, so a state-changing request authenticated
 * this way must also come from an allowed {@code Origin}. An {@code X-API-Key}
 * request needs no such check, because a browser never adds that header unasked.
 * The cookie is removed before routing: no internal service needs the secret.
 *
 * <h2>The trust model downstream</h2>
 * Internal services trust {@code X-Tenant-ID} because it arrives alongside
 * {@code X-Internal-Token}, which only this filter injects and which every
 * internal service independently requires (see each service's
 * {@code InternalAuthFilter}). The header is therefore only trustworthy to the
 * extent that the internal token is secret.
 *
 * <p>The stronger design — a short-lived signed assertion (JWT) that each
 * service <em>verifies</em> rather than trusts — is deliberately not implemented
 * here. It is a real improvement, but it would require key distribution and
 * verification logic in all six Java services and all seven Python agents, and
 * it defends against an attacker who already holds the internal token, i.e. one
 * who is already inside the trust boundary. Fixing the header-spoofing hole
 * available to any external caller comes first; that is what this change does.
 *
 * <h2>Webhooks</h2>
 * {@code /webhook/**} cannot present an API key — GitHub and Sentry have no way
 * to hold a GitOracle credential — so it is authenticated by HMAC signature at
 * the error-ingestor instead (see {@code HmacVerifier}). Because no key is
 * presented, no tenant can be derived, so this filter injects <b>no</b>
 * {@code X-Tenant-ID} for webhook routes and merely strips any the caller sent.
 * Webhook deliveries consequently still land on the default tenant downstream.
 * That is a known remaining gap, not an oversight: resolving it properly means
 * per-installation webhook secrets stored per tenant, so the tenant is derived
 * from the <em>verified</em> installation ID. That work belongs with the
 * per-installation secret storage the webhook change already flagged as
 * deferred, and it is tracked as such.
 */
@Component
public class TenantContextFilter implements GlobalFilter, Ordered {

    private static final Logger logger = LoggerFactory.getLogger(TenantContextFilter.class);

    static final String TENANT_HEADER = "X-Tenant-ID";
    static final String SCOPES_HEADER = "X-Scopes";
    static final String INTERNAL_TOKEN_HEADER = "X-Internal-Token";
    static final String USER_HEADER = "X-User-ID";
    static final String SESSION_HEADER = "X-Session-ID";

    private static final String LOGOUT_PATH = "/api/v1/auth/logout";

    /** Who a request was authenticated as, by either credential. */
    private record Caller(UUID tenantId, Set<String> scopes, String label, UUID userId, UUID sessionId) {}

    private final ApiKeyAuthenticator authenticator;
    private final SessionAuthenticator sessionAuthenticator;

    /** Same setting as the CORS configuration in application.yml. */
    @Value("${gitoracle.allowed-origins:http://localhost:5173}")
    private String allowedOrigins;

    @Value("${gitoracle.internal-token:}")
    private String configuredInternalToken;

    /**
     * Refuse to start without the internal token. It used to default to empty,
     * so a misconfigured service booted "healthy" and then answered every
     * internal call with 401 — a failure only visible one request at a time,
     * far from its cause. A clear startup error names the missing setting.
     */
    @jakarta.annotation.PostConstruct
    void requireInternalTokenConfigured() {
        if (configuredInternalToken == null || configuredInternalToken.isBlank()) {
            throw new IllegalStateException("GITORACLE_INTERNAL_TOKEN is not set. Every internal "
                + "service call would be rejected with 401, so this service refuses to start. "
                + "Set it in the root .env (any long random string, the same for every service).");
        }
        if (configuredInternalToken != null && (configuredInternalToken.startsWith("change_me") || configuredInternalToken.startsWith("your_"))) {
            throw new IllegalStateException("GITORACLE_INTERNAL_TOKEN still has the placeholder value from "
                + ".env.example, which is public, so anyone could call this service with it. This service "
                + "refuses to start. ./start_local.sh replaces it for you, or set your own with: openssl rand -hex 32");
        }
    }

    /** Paths that bypass authentication entirely. */
    private static final java.util.List<String> EXEMPT_PATHS = java.util.List.of(
        "/actuator", "/health", "/api/v1/health"
    );

    public TenantContextFilter(ApiKeyAuthenticator authenticator, SessionAuthenticator sessionAuthenticator) {
        this.authenticator = authenticator;
        this.sessionAuthenticator = sessionAuthenticator;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();

        if (isExempt(path)) {
            return chain.filter(exchange);
        }

        // Same fail-closed posture as before: every internal service now rejects
        // requests without this, so an unconfigured value would make every
        // proxied request fail downstream anyway — better to say so clearly here
        // than let a client see a confusing 401 from whichever service happened
        // to be routed to.
        if (configuredInternalToken == null || configuredInternalToken.isBlank()) {
            logger.error("Request rejected: GITORACLE_INTERNAL_TOKEN is not configured on the server — refusing all traffic rather than proxying requests every internal service will itself reject.");
            return deny(exchange, HttpStatus.UNAUTHORIZED, "Server misconfiguration: GITORACLE_INTERNAL_TOKEN is not set.");
        }

        // Also a GRANT (it skips the X-API-Key requirement), so it is matched
        // the same defensive way as isExempt: the raw path must already be
        // canonical and match on a segment boundary. Otherwise
        // "/webhook/../api/v1/admin/tenants" could claim the webhook exemption
        // on its literal prefix while resolving to an entirely different route.
        if (RequestPaths.isCanonical(path) && RequestPaths.isUnderPrefix(path, "/webhook")) {
            // No key, so no derivable tenant. Strip whatever the caller sent and
            // forward with the internal token only.
            return chain.filter(exchange.mutate().request(sanitised(exchange, null).build()).build());
        }

        // A grant too, so the same care: the raw path must match exactly. The state
        // cookie the callback needs stays on the request (sanitised(null) keeps it).
        if (RequestPaths.isCanonical(path)
                && RouteAccess.resolve(exchange.getRequest().getMethod().name(), path) == RouteAccess.Access.PUBLIC) {
            return chain.filter(exchange.mutate().request(sanitised(exchange, null).build()).build());
        }

        String presentedKey = exchange.getRequest().getHeaders().getFirst("X-API-Key");

        if (presentedKey == null || presentedKey.isBlank()) {
            HttpCookie sessionCookie = exchange.getRequest().getCookies().getFirst(SessionTokens.COOKIE_NAME);
            if (sessionCookie != null) {
                return authenticateSession(exchange, chain, path, sessionCookie.getValue());
            }
        }

        return authenticator.authenticate(presentedKey)
            .flatMap(maybePrincipal -> {
                if (maybePrincipal.isEmpty()) {
                    logger.warn("Request rejected: invalid or missing X-API-Key for path={}", path);
                    return deny(exchange, HttpStatus.UNAUTHORIZED, "Invalid or missing X-API-Key header.");
                }

                ApiKeyAuthenticator.Principal principal = maybePrincipal.get();
                return forward(exchange, chain, path, new Caller(
                    principal.tenantId(), principal.scopes(), "key " + principal.keyPrefix(), null, null));
            });
    }

    private Mono<Void> authenticateSession(ServerWebExchange exchange, GatewayFilterChain chain, String path, String token) {
        if (!isSafeMethod(exchange.getRequest().getMethod()) && !hasAllowedOrigin(exchange)) {
            logger.warn("Request rejected: session-authenticated {} to path={} from a disallowed origin",
                exchange.getRequest().getMethod(), path);
            return deny(exchange, HttpStatus.FORBIDDEN, "This origin may not make changes with a session cookie.");
        }

        return sessionAuthenticator.authenticate(token)
            .flatMap(maybeSession -> {
                if (maybeSession.isEmpty()) {
                    logger.warn("Request rejected: invalid or expired session for path={}", path);
                    return deny(exchange, HttpStatus.UNAUTHORIZED, "Invalid or expired session.");
                }

                SessionAuthenticator.SessionPrincipal session = maybeSession.get();
                Mono<Void> forwarded = forward(exchange, chain, path, new Caller(session.tenantId(), session.scopes(),
                    "session " + session.sessionId(), session.userId(), session.sessionId()));
                // After the orchestrator has revoked the row: evicting any earlier
                // could let a concurrent request cache the session again.
                return LOGOUT_PATH.equals(path)
                    ? forwarded.doFinally(signal -> sessionAuthenticator.evict(token))
                    : forwarded;
            });
    }

    private Mono<Void> forward(ServerWebExchange exchange, GatewayFilterChain chain, String path, Caller caller) {
        RouteAccess.Access access = RouteAccess.resolve(exchange.getRequest().getMethod().name(), path);
        if (!RouteAccess.allows(access, caller.scopes(), caller.sessionId() != null)) {
            logger.warn("Request rejected: {} needs {} for {} path={}",
                caller.label(), access, exchange.getRequest().getMethod(), path);
            return deny(exchange, HttpStatus.FORBIDDEN, RouteAccess.refusal(access));
        }

        logger.debug("Authorized request tenant={} caller={} path={}", caller.tenantId(), caller.label(), path);

        ServerHttpRequest mutated = sanitised(exchange, caller).build();
        return chain.filter(exchange.mutate().request(mutated).build());
    }

    private static boolean isSafeMethod(HttpMethod method) {
        return HttpMethod.GET.equals(method) || HttpMethod.HEAD.equals(method) || HttpMethod.OPTIONS.equals(method);
    }

    /** Browsers always send Origin on a state-changing request, so a missing one is refused. */
    private boolean hasAllowedOrigin(ServerWebExchange exchange) {
        String origin = exchange.getRequest().getHeaders().getOrigin();
        return origin != null && Arrays.stream(allowedOrigins.split(","))
            .map(String::trim)
            .anyMatch(origin::equals);
    }

    /**
     * Builds the outbound request with every client-controllable identity header
     * removed and, where a principal was resolved, replaced by the server's own
     * values.
     *
     * <p>Explicit remove-then-set rather than relying on {@code .header()}'s
     * add-vs-replace semantics: these headers must never end up multi-valued
     * with a client's attempted value still sitting alongside the real one — a
     * downstream service reading "the first value" would then read the
     * attacker's.
     */
    private ServerHttpRequest.Builder sanitised(ServerWebExchange exchange, Caller caller) {
        String internalToken = configuredInternalToken;
        return exchange.getRequest().mutate().headers(headers -> {
            headers.remove(TENANT_HEADER);
            headers.remove(SCOPES_HEADER);
            headers.remove(INTERNAL_TOKEN_HEADER);
            headers.remove(USER_HEADER);
            headers.remove(SESSION_HEADER);

            if (caller != null) {
                headers.remove(HttpHeaders.COOKIE);
                headers.set(TENANT_HEADER, caller.tenantId().toString());
                headers.set(SCOPES_HEADER, ApiKey.Scopes.join(caller.scopes()));
                if (caller.userId() != null) {
                    headers.set(USER_HEADER, caller.userId().toString());
                    headers.set(SESSION_HEADER, caller.sessionId().toString());
                }
            }
            headers.set(INTERNAL_TOKEN_HEADER, internalToken);
        });
    }

    /**
     * Exact match, or a match on a full path segment, and only for a path that
     * is already canonical.
     *
     * <p>A plain {@code startsWith} meant {@code /actuatorFOO} — and, more to the
     * point, {@code /healthcheck-bypass} or any route an attacker could get
     * mapped — skipped authentication entirely, because the exempt list was
     * tested as a raw string prefix rather than a path prefix.
     *
     * <p>Exempting is a GRANT, so unlike {@link RouteAccess} this deliberately
     * does <em>not</em> canonicalise before comparing: normalising first would
     * exempt {@code /actuator/../api/v1/admin/tenants}, whose literal prefix
     * looks exempt but which resolves to an admin route. Requiring the raw path
     * to already be canonical makes anything ambiguous fall through to normal
     * authentication instead.
     */
    private boolean isExempt(String path) {
        if (!RequestPaths.isCanonical(path)) return false;
        return EXEMPT_PATHS.stream().anyMatch(p -> path.equals(p) || path.startsWith(p + "/"));
    }

    private Mono<Void> deny(ServerWebExchange exchange, HttpStatus status, String message) {
        exchange.getResponse().setStatusCode(status);
        exchange.getResponse().getHeaders().add("Content-Type", "application/json");
        var body = "{\"error\":\"" + status.getReasonPhrase() + "\",\"message\":\"" + message + "\"}";
        var buffer = exchange.getResponse().bufferFactory()
            .wrap(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return exchange.getResponse().writeWith(Mono.just(buffer));
    }

    @Override
    public int getOrder() {
        return -1; // Run before routing filters
    }
}
