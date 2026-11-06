package ai.gitoracle.core.model.postgres;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** What a person may do inside one organisation. Higher roles include everything below them. */
public enum OrgRole {
    VIEWER(List.of(ApiKey.Scopes.JOBS_READ)),
    MEMBER(List.of(ApiKey.Scopes.JOBS_READ, ApiKey.Scopes.JOBS_WRITE)),
    ADMIN(List.of(ApiKey.Scopes.JOBS_READ, ApiKey.Scopes.JOBS_WRITE, ApiKey.Scopes.ORG_ADMIN)),
    OWNER(List.of(ApiKey.Scopes.JOBS_READ, ApiKey.Scopes.JOBS_WRITE, ApiKey.Scopes.ORG_ADMIN, ApiKey.Scopes.ORG_OWNER));

    private final List<String> scopes;

    OrgRole(List<String> scopes) {
        this.scopes = scopes;
    }

    public Collection<String> scopes() {
        return scopes;
    }

    public boolean atLeast(OrgRole other) {
        return compareTo(other) >= 0;
    }

    /** The scopes a session carries. Being a platform admin is separate from any organisation's role. */
    public Set<String> sessionScopes(boolean platformAdmin) {
        Set<String> result = new LinkedHashSet<>(scopes);
        if (platformAdmin) result.add(ApiKey.Scopes.PLATFORM_ADMIN);
        return result;
    }
}
