package ai.gitoracle.gateway.filter;

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
 * Global filter that validates every inbound API request.
 *
 * Requires two headers:
 *   X-API-Key   — secret key configured in application.yml (gitoracle.api-key)
 *   X-Tenant-ID — UUID identifying the tenant (used downstream)
 *
 * Health/actuator endpoints are exempt from auth.
 *
 * Also injects X-Internal-Token on every proxied request once the checks
 * above pass — every internal service (orchestrator, error-ingestor,
 * git-forensics, and every Python agent behind them) now requires that
 * header itself, since previously each was directly reachable on its own
 * port with no auth of its own, making this gateway's own checks advisory
 * rather than an actual boundary. Any X-Internal-Token an external caller
 * sent is stripped first, so a client can't hand itself the internal trust
 * boundary just by setting the header — only this filter gets to set it.
 */
@Component
public class TenantContextFilter implements GlobalFilter, Ordered {

    private static final Logger logger = LoggerFactory.getLogger(TenantContextFilter.class);

    @Value("${gitoracle.api-key:}")
    private String configuredApiKey;

    @Value("${gitoracle.internal-token:}")
    private String configuredInternalToken;

    // Paths that bypass auth entirely
    private static final java.util.List<String> EXEMPT_PATHS = java.util.List.of(
        "/actuator", "/health", "/api/v1/health"
    );

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();

        // ── Exempt health / actuator endpoints ────────────────────────────────
        if (EXEMPT_PATHS.stream().anyMatch(path::startsWith)) {
            return chain.filter(exchange);
        }

        // ── API Key validation ─────────────────────────────────────────────────
        // Fails CLOSED: an unconfigured key used to mean "skip the check entirely"
        // (auth silently disabled for every request, confirmed live — GITORACLE_API_KEY
        // was blank in .env), which is backwards for a gate whose whole job is to
        // reject unauthenticated traffic. A missing server-side key is now itself a
        // rejection, not a bypass.
        if (configuredApiKey == null || configuredApiKey.isBlank()) {
            logger.error("Request rejected: GITORACLE_API_KEY is not configured on the server — refusing all traffic rather than allowing it unauthenticated.");
            return unauthorized(exchange, "Server misconfiguration: GITORACLE_API_KEY is not set.");
        }
        String incomingKey = exchange.getRequest().getHeaders().getFirst("X-API-Key");
        if (incomingKey == null || !incomingKey.equals(configuredApiKey)) {
            logger.warn("Request rejected: Invalid or missing X-API-Key for path={}", path);
            return unauthorized(exchange, "Invalid or missing X-API-Key header.");
        }

        // ── Internal token ────────────────────────────────────────────────────
        // Same fail-closed posture as X-API-Key above: every internal service
        // now rejects requests without this, so an unconfigured value here
        // would make every proxied request fail downstream anyway — better to
        // say so clearly at the gateway than let a client see a confusing 401
        // from whatever service happened to be routed to.
        if (configuredInternalToken == null || configuredInternalToken.isBlank()) {
            logger.error("Request rejected: GITORACLE_INTERNAL_TOKEN is not configured on the server — refusing all traffic rather than proxying requests every internal service will itself reject.");
            return unauthorized(exchange, "Server misconfiguration: GITORACLE_INTERNAL_TOKEN is not set.");
        }

        // ── Tenant ID ──────────────────────────────────────────────────────────
        String tenantId = exchange.getRequest().getHeaders().getFirst("X-Tenant-ID");
        if (tenantId == null || tenantId.isBlank()) {
            tenantId = "00000000-0000-0000-0000-000000000000"; // default single-tenant
        }

        logger.debug("Authorized request tenant={} path={}", tenantId, path);

        // Explicit remove-then-set rather than relying on .header()'s
        // add-vs-replace semantics for a header the client may have already
        // sent — this must never end up multi-valued with the client's own
        // attempted value still present alongside the real one.
        String internalToken = configuredInternalToken;
        ServerHttpRequest mutated = exchange.getRequest().mutate()
            .header("X-Tenant-ID", tenantId)
            .headers(headers -> {
                headers.remove("X-Internal-Token");
                headers.set("X-Internal-Token", internalToken);
            })
            .build();

        return chain.filter(exchange.mutate().request(mutated).build());
    }

    private Mono<Void> unauthorized(ServerWebExchange exchange, String message) {
        exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
        exchange.getResponse().getHeaders().add("Content-Type", "application/json");
        var body = "{\"error\":\"Unauthorized\",\"message\":\"" + message + "\"}";
        var buffer = exchange.getResponse().bufferFactory()
            .wrap(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return exchange.getResponse().writeWith(Mono.just(buffer));
    }

    @Override
    public int getOrder() {
        return -1; // Run before routing filters
    }
}
