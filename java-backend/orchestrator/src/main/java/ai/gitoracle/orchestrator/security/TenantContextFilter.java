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
import java.util.UUID;

/**
 * Establishes {@link TenantContext} for the duration of each request from the
 * {@code X-Tenant-ID} and {@code X-Scopes} headers the api-gateway injected.
 *
 * <p>These headers are trusted here — but only because {@code InternalAuthFilter}
 * has already rejected anything arriving without a valid {@code X-Internal-Token},
 * and the gateway strips any client-supplied copy of both headers before setting
 * its own. The chain is: caller proves possession of an API key → gateway
 * resolves that key's tenant → gateway forwards the tenant alongside the
 * internal token → this service accepts it.
 *
 * <h2>Direct callers that are not the gateway</h2>
 * The Python agents and the eval harness call this service directly with the
 * internal token and generally send no tenant header. Rather than reject them —
 * which would break the pipeline — a missing header leaves the context unset,
 * and tenant-scoped read endpoints then fall back to the default tenant
 * explicitly (see {@link #resolveTenant}). This is the residual weakness in the
 * current design and is called out plainly: anything holding the internal token
 * can act as the default tenant. Narrowing that requires the agents to thread a
 * tenant through their HTTP calls the way they already thread it through Kafka
 * payloads, which is a broader change than this one.
 */
@Component
public class TenantContextFilter extends OncePerRequestFilter {

    private static final Logger logger = LoggerFactory.getLogger(TenantContextFilter.class);

    private static final List<String> EXEMPT_PATHS = List.of("/actuator", "/health", "/api/v1/health");

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
            TenantContext.set(resolveTenant(request), ApiKey.Scopes.parse(request.getHeader("X-Scopes")));
            chain.doFilter(request, response);
        } finally {
            // Unconditional: the orchestrator runs on a pooled thread, so a
            // value left behind here would be read by whatever request lands on
            // this thread next — the one failure mode of a ThreadLocal that is
            // an actual cross-tenant leak rather than merely a wrong answer.
            TenantContext.clear();
        }
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
