package ai.gitoracle.orchestrator.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/**
 * Requires a shared-secret X-Internal-Token header on every request to this
 * service. Every one of GitOracle's 13 services used to bind 0.0.0.0 with no
 * auth of its own — the api-gateway's X-API-Key check only ever protected
 * traffic that went through the gateway, but every service was also directly
 * reachable on its own port, so the gateway was advisory rather than an
 * actual boundary. This filter, present on every internal service, closes
 * that: the api-gateway is the only caller that gets to skip it, because it's
 * the one that INJECTS this header (see TenantContextFilter) after a request
 * has already passed X-API-Key/tenant validation — nothing else should ever
 * need to send X-Internal-Token from outside this service's own trust
 * boundary.
 *
 * Duplicated across orchestrator/error-ingestor/git-forensics/test-runner/
 * github-bot rather than shared via git-oracle-core: this is a Servlet-based
 * OncePerRequestFilter, and git-oracle-core has no servlet API on its
 * classpath — api-gateway also depends on git-oracle-core but is a WebFlux
 * (Spring Cloud Gateway) app, so pulling spring-boot-starter-web in there to
 * make this compile would risk exactly the MVC/WebFlux auto-configuration
 * conflict that keeps api-gateway reactive today.
 */
@Component
public class InternalAuthFilter extends OncePerRequestFilter {

    private static final Logger logger = LoggerFactory.getLogger(InternalAuthFilter.class);

    private static final List<String> EXEMPT_PATHS = List.of("/actuator", "/health", "/api/v1/health");

    @Value("${gitoracle.internal-token:}")
    private String configuredToken;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        if (EXEMPT_PATHS.stream().anyMatch(exempt -> path.equals(exempt) || path.startsWith(exempt + "/"))) {
            chain.doFilter(request, response);
            return;
        }

        // Fails CLOSED: an unconfigured token means "refuse everything", not
        // "skip the check" — the same posture TenantContextFilter takes for
        // GITORACLE_API_KEY, for the same reason (a missing server-side
        // secret is a misconfiguration, not an invitation to run open).
        if (configuredToken == null || configuredToken.isBlank()) {
            logger.error("Request rejected: GITORACLE_INTERNAL_TOKEN is not configured on this service — " +
                "refusing all traffic rather than allowing it unauthenticated. path={}", path);
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Server misconfiguration: GITORACLE_INTERNAL_TOKEN is not set.");
            return;
        }

        String incoming = request.getHeader("X-Internal-Token");
        if (incoming == null || !constantTimeEquals(incoming, configuredToken)) {
            logger.warn("Request rejected: invalid or missing X-Internal-Token for path={}", path);
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Invalid or missing X-Internal-Token header.");
            return;
        }

        chain.doFilter(request, response);
    }

    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
