package ai.gitoracle.orchestrator.security;

import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/** Lets tests outside this package run code as a tenant; the setters are deliberately package-private. */
public final class TenantContextTestSupport {

    private TenantContextTestSupport() {}

    public static <T> T runAs(UUID tenantId, Set<String> scopes, Supplier<T> body) {
        return runAs(tenantId, scopes, null, body);
    }

    public static <T> T runAs(UUID tenantId, Set<String> scopes, UUID userId, Supplier<T> body) {
        TenantContext.set(tenantId, scopes, true, userId);
        try {
            return body.get();
        } finally {
            TenantContext.clear();
        }
    }
}
