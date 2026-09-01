package ai.gitoracle.orchestrator.security;

import ai.gitoracle.core.model.postgres.ApiKey;
import ai.gitoracle.core.model.postgres.Tenant;
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
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Establishes {@link TenantContext} for the duration of each request from the
 * {@code X-Tenant-ID} and {@code X-Scopes} headers the api-gateway injected, and
 * enforces {@code platform:admin} on {@code /api/v1/admin/**} directly at this
 * service rather than trusting the gateway's copy of that same check alone.
 *
 * <h2>Why the admin gate is duplicated here (H3)</h2>
 * The gateway ({@code TenantContextFilter} in api-gateway) already rejects any
 * {@code /api/v1/admin/**} request whose resolved key lacks
 * {@code platform:admin}. That check alone used to be the <em>only</em> one:
 * {@code AdminController} (tenant creation, budget/risk config, prompt-version
 * switching) had no authorization of its own, so it was only ever as protected
 * as "this request happened to go through the gateway." C5 already closed the
 * bigger half of that gap by binding every internal service to loopback and
 * requiring {@code X-Internal-Token} — but anything that legitimately holds
 * that token (an operator's script, an internal tool, a Python agent making a
 * direct call) reaches this service exactly as the gateway does, and nothing
 * stopped it from calling {@code /api/v1/admin/**} without ever having gone
 * through the gateway's scope check at all.
 *
 * <p>The fix is not a second copy of "check X-API-Key" — an internal caller by
 * definition has no API key, only the internal token. It is enforcing the same
 * <em>scope</em> requirement independently of how the request arrived: this
 * filter denies any {@code /api/v1/admin/**} request whose {@code X-Scopes}
 * does not carry {@code platform:admin}, full stop. A caller with no
 * {@code X-Scopes} header at all — which is the default for every internal
 * caller that is not the gateway — has no scopes and is therefore denied,
 * which is the correct default: administration is opt-in, not something you
 * get by being inside the trust boundary.
 *
 * <h2>Everything else</h2>
 * These headers are trusted here — but only because {@code InternalAuthFilter}
 * has already rejected anything arriving without a valid {@code X-Internal-Token},
 * and the gateway strips any client-supplied copy of both headers before setting
 * its own. The chain is: caller proves possession of an API key → gateway
 * resolves that key's tenant and scopes → gateway forwards them alongside the
 * internal token → this service accepts them.
 *
 * <h2>Direct callers that are not the gateway</h2>
 * The Python agents and the eval harness call this service directly with the
 * internal token and generally send no tenant header. Rather than reject them —
 * which would break the pipeline — a missing header leaves the context unset,
 * and tenant-scoped read endpoints then fall back to the default tenant
 * explicitly (see {@link #resolveTenant}). This is the residual weakness in the
 * current design and is called out plainly: anything holding the internal token
 * can act as the default tenant on ordinary (non-admin) endpoints. Narrowing
 * that requires the agents to thread a tenant through their HTTP calls the way
 * they already thread it through Kafka payloads, which is a broader change than
 * this one. Admin endpoints do not get this same default-tenant fallback for
 * scopes: a missing {@code X-Scopes} header is empty scopes, not admin scopes.
 */
@Component
public class TenantContextFilter extends OncePerRequestFilter {

    private static final Logger logger = LoggerFactory.getLogger(TenantContextFilter.class);

    private static final List<String> EXEMPT_PATHS = List.of("/actuator", "/health", "/api/v1/health");
    private static final String ADMIN_PATH_PREFIX = "/api/v1/admin";

    @Value("${gitoracle.default-tenant-id:00000000-0000-0000-0000-000000000000}")
    private String defaultTenantId;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        String path = request.getRequestURI();
        if (EXEMPT_PATHS.stream().anyMatch(exempt -> path.equals(exempt) || path.startsWith(exempt + "/"))) {
            chain.doFilter(request, response);
            return;
        }

        try {
            UUID tenantId = resolveTenant(request);
            Set<String> scopes = ApiKey.Scopes.parse(request.getHeader("X-Scopes"));
            TenantContext.set(tenantId, scopes);

            if (isAdminPath(path) && !scopes.contains(ApiKey.Scopes.PLATFORM_ADMIN)) {
                logger.warn("Request rejected: platform:admin scope required for admin path={} (resolved tenant={})",
                    path, tenantId);
                response.sendError(HttpServletResponse.SC_FORBIDDEN,
                    "This request does not carry the platform:admin scope.");
                return;
            }

            chain.doFilter(request, response);
        } finally {
            // Unconditional: the orchestrator runs on a pooled thread, so a
            // value left behind here would be read by whatever request lands on
            // this thread next — the one failure mode of a ThreadLocal that is
            // an actual cross-tenant leak rather than merely a wrong answer.
            TenantContext.clear();
        }
    }

    private boolean isAdminPath(String path) {
        return path.equals(ADMIN_PATH_PREFIX) || path.startsWith(ADMIN_PATH_PREFIX + "/");
    }

    private UUID resolveTenant(HttpServletRequest request) {
        String header = request.getHeader("X-Tenant-ID");
        if (header == null || header.isBlank()) {
            return defaultTenant();
        }
        try {
            return UUID.fromString(header.trim());
        } catch (IllegalArgumentException e) {
            // A malformed value can only come from a caller inside the trust
            // boundary sending nonsense; treat it as absent rather than trusting
            // a partially-parsed identity.
            logger.warn("Ignoring malformed X-Tenant-ID on path={}", request.getRequestURI());
            return defaultTenant();
        }
    }

    private UUID defaultTenant() {
        try {
            return UUID.fromString(defaultTenantId);
        } catch (IllegalArgumentException e) {
            return Tenant.DEFAULT_ID;
        }
    }
}
