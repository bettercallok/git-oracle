package ai.gitoracle.orchestrator.security;

import java.util.Set;
import java.util.UUID;

/** Lets tests outside this package act as a request from a given tenant. */
public final class TestTenantContext {

    private TestTenantContext() {}

    public static void set(UUID tenantId) {
        TenantContext.set(tenantId, Set.of());
    }

    public static void clear() {
        TenantContext.clear();
    }
}
