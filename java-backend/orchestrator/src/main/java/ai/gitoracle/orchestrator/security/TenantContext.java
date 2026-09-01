package ai.gitoracle.orchestrator.security;

import ai.gitoracle.core.model.postgres.ApiKey;

import java.util.Set;
import java.util.UUID;

/**
 * The tenant the current request belongs to, for the duration of that request.
 *
 * <p>Populated by {@link TenantContextFilter} from the {@code X-Tenant-ID}
 * header, which the api-gateway derives from the authenticated API key and which
 * arrives alongside a valid {@code X-Internal-Token} — a request that reaches
 * this service without that token has already been rejected, so the header
 * cannot have come from an external caller choosing its own tenant.
 *
 * <h2>Why a ThreadLocal, and where it does not apply</h2>
 * The orchestrator is a Servlet (Spring MVC) application, so one request is one
 * thread, and a ThreadLocal is the ordinary way to carry request-scoped context
 * to code that is not a controller. The filter clears it in a {@code finally}
 * block; on a pooled thread a leaked value would be read by the <em>next</em>
 * request, which is the one failure mode that would be a cross-tenant leak, so
 * the clear is unconditional.
 *
 * <p>Crucially, this is <b>request context only</b>. The orchestrator's Kafka
 * listeners run on consumer threads with no request and therefore no tenant
 * context; they must take the tenant from the event payload, which is why
 * {@code ErrorIngestedEvent} carries {@code tenantId} explicitly. Any future
 * code that reads {@link #requireTenantId()} from a Kafka listener is a bug and
 * will fail loudly rather than quietly defaulting.
 */
public final class TenantContext {

    private record Context(UUID tenantId, Set<String> scopes) {}

    private static final ThreadLocal<Context> CURRENT = new ThreadLocal<>();

    private TenantContext() {}

    static void set(UUID tenantId, Set<String> scopes) {
        CURRENT.set(new Context(tenantId, scopes == null ? Set.of() : scopes));
    }

    static void clear() {
        CURRENT.remove();
    }

    /** The current request's tenant, or null outside a request (e.g. a Kafka listener). */
    public static UUID tenantId() {
        Context c = CURRENT.get();
        return c == null ? null : c.tenantId();
    }

    /**
     * The current request's tenant, failing loudly if there is none.
     *
     * <p>Deliberately throws instead of falling back to the default tenant. A
     * silent default is exactly how the zero UUID came to be written onto jobs
     * that belonged to somebody else.
     */
    public static UUID requireTenantId() {
        UUID id = tenantId();
        if (id == null) {
            throw new IllegalStateException(
                "No tenant context on this thread. A tenant-scoped operation was invoked outside an " +
                "authenticated request (Kafka listeners must take the tenant from the event payload).");
        }
        return id;
    }

    public static Set<String> scopes() {
        Context c = CURRENT.get();
        return c == null ? Set.of() : c.scopes();
    }

    public static boolean isPlatformAdmin() {
        return scopes().contains(ApiKey.Scopes.PLATFORM_ADMIN);
    }
}
