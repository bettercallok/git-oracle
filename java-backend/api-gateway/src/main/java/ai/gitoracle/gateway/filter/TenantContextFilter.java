package ai.gitoracle.gateway.filter;

import ai.gitoracle.core.model.postgres.ApiKey;
import ai.gitoracle.gateway.security.ApiKeyAuthenticator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

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
 *   <li>Scope-gated paths ({@code /api/v1/admin/**}) require {@code platform:admin}.</li>
 * </ul>
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

    private final ApiKeyAuthenticator authenticator;

    @Value("${gitoracle.internal-token:}")
    private String configuredInternalToken;

    /** Paths that bypass authentication entirely. */
    private static final java.util.List<String> EXEMPT_PATHS = java.util.List.of(
        "/actuator", "/health", "/api/v1/health"
    );

    public TenantContextFilter(ApiKeyAuthenticator authenticator) {
        this.authenticator = authenticator;
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

        if (path.startsWith("/webhook")) {
            // No key, so no derivable tenant. Strip whatever the caller sent and
            // forward with the internal token only.
            return chain.filter(exchange.mutate().request(sanitised(exchange, null).build()).build());
        }

        String presentedKey = exchange.getRequest().getHeaders().getFirst("X-API-Key");

        return authenticator.authenticate(presentedKey)
            .flatMap(maybePrincipal -> {
                if (maybePrincipal.isEmpty()) {
                    logger.warn("Request rejected: invalid or missing X-API-Key for path={}", path);
                    return deny(exchange, HttpStatus.UNAUTHORIZED, "Invalid or missing X-API-Key header.");
                }

                ApiKeyAuthenticator.Principal principal = maybePrincipal.get();

                if (isAdminPath(path) && !principal.hasScope(ApiKey.Scopes.PLATFORM_ADMIN)) {
                    logger.warn("Request rejected: key {} lacks {} for admin path={}",
                        principal.keyPrefix(), ApiKey.Scopes.PLATFORM_ADMIN, path);
                    return deny(exchange, HttpStatus.FORBIDDEN, "This API key does not carry the platform:admin scope.");
                }

                logger.debug("Authorized request tenant={} key={} path={}",
                    principal.tenantId(), principal.keyPrefix(), path);

                ServerHttpRequest mutated = sanitised(exchange, principal).build();
                return chain.filter(exchange.mutate().request(mutated).build());
            });
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
    private ServerHttpRequest.Builder sanitised(ServerWebExchange exchange, ApiKeyAuthenticator.Principal principal) {
        String internalToken = configuredInternalToken;
        return exchange.getRequest().mutate().headers(headers -> {
            headers.remove(TENANT_HEADER);
            headers.remove(SCOPES_HEADER);
            headers.remove(INTERNAL_TOKEN_HEADER);

            if (principal != null) {
                headers.set(TENANT_HEADER, principal.tenantId().toString());
                headers.set(SCOPES_HEADER, ApiKey.Scopes.join(principal.scopes()));
            }
            headers.set(INTERNAL_TOKEN_HEADER, internalToken);
        });
    }

    /**
     * Exact match, or a match on a full path segment.
     *
     * <p>A plain {@code startsWith} meant {@code /actuatorFOO} — and, more to the
     * point, {@code /healthcheck-bypass} or any route an attacker could get
     * mapped — skipped authentication entirely, because the exempt list was
     * tested as a raw string prefix rather than a path prefix.
     */
    private boolean isExempt(String path) {
        return EXEMPT_PATHS.stream().anyMatch(p -> path.equals(p) || path.startsWith(p + "/"));
    }

    private boolean isAdminPath(String path) {
        return path.equals("/api/v1/admin") || path.startsWith("/api/v1/admin/");
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
