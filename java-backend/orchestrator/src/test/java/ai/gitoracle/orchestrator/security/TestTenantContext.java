package ai.gitoracle.orchestrator.security;

import java.util.Set;
import java.util.UUID;

/** Lets tests outside this package act as a request from a given tenant. */
public final class TestTenantContext {

    private TestTenantContext() {}

    public static void set(UUID tenantId) {
        TenantContext.set(tenantId, Set.of());
    }

    /** A direct internal caller (a Python agent): no X-Tenant-ID, default tenant filled in. */
    public static void setInternalCaller(UUID defaultTenantId) {
        TenantContext.set(defaultTenantId, Set.of(), false);
    }

    public static void clear() {
        TenantContext.clear();
    }
}
