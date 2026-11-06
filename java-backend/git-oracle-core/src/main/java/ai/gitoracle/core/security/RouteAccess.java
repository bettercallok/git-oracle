package ai.gitoracle.core.security;

import ai.gitoracle.core.model.postgres.ApiKey;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * What every API route requires, in one place, decided by the api-gateway.
 *
 * <p>{@code jobs:read} and {@code jobs:write} used to be stored on keys and checked
 * nowhere, so a read-only key could start jobs. Routes that only agents should call
 * were also reachable by anyone signed in, so a user could write fake entries into
 * their own job's timeline or inflate its token count.
 *
 * <p>The default is the strict one: an unlisted {@code GET} needs {@code jobs:read}
 * and anything else needs {@code jobs:write}. A new route can therefore only be
 * too locked down, never accidentally open. RouteAccessMatrixTest fails until a new
 * route is classified.
 */
public final class RouteAccess {

    public enum Access {
        /** The steps of signing in, which have no credential yet. */
        PUBLIC,
        /** Any valid credential, whatever its scopes. */
        AUTHENTICATED,
        READ,
        WRITE,
        /** An organisation's admin or owner, or a platform admin. */
        ORG_ADMIN,
        /** Platform administration, across every organisation. */
        ADMIN,
        /** A person signed in with GitHub; an API key is refused. */
        SESSION,
        /** A signed-in person who can also write, so not a viewer. */
        SESSION_WRITE,
        /** Service-to-service only: never allowed through the gateway. */
        INTERNAL
    }

    private static final Set<String> PUBLIC_PATHS = Set.of(
        "/api/v1/auth/config", "/api/v1/auth/github/login", "/api/v1/auth/github/callback");

    /** Agents call these directly on the orchestrator with the internal token. */
    private static final List<Pattern> INTERNAL_WRITES = List.of(
        Pattern.compile("/api/v1/jobs/[^/]+/events"),
        Pattern.compile("/api/v1/jobs/[^/]+/prompt-version"),
        Pattern.compile("/api/v1/budget/[^/]+/record"));

    private RouteAccess() {}

    /** Works on the path a router would resolve, so encoding tricks can't change the answer. */
    public static Access resolve(String method, String rawPath) {
        String path = RequestPaths.canonicalize(rawPath);
        if (path.length() > 1 && path.endsWith("/")) path = path.substring(0, path.length() - 1);
        boolean read = "GET".equals(method) || "HEAD".equals(method) || "OPTIONS".equals(method);

        if (PUBLIC_PATHS.contains(path)) return read ? Access.PUBLIC : Access.WRITE;
        if (path.equals("/api/v1/auth") || path.startsWith("/api/v1/auth/")) return Access.AUTHENTICATED;
        if (path.equals("/api/v1/admin") || path.startsWith("/api/v1/admin/")) return Access.ADMIN;
        if (path.equals("/api/v1/keys") || path.startsWith("/api/v1/keys/")) {
            return read ? Access.SESSION : Access.SESSION_WRITE;
        }
        if (path.equals("/api/v1/org") || path.startsWith("/api/v1/org/")) {
            return read && (path.equals("/api/v1/org") || path.equals("/api/v1/org/members")) ? Access.READ : Access.ORG_ADMIN;
        }
        // An eval result says how good the system is, so not everyone may publish one.
        if (path.equals("/api/v1/evals") && !read) return Access.ORG_ADMIN;
        if (!read) {
            String candidate = path;
            if (INTERNAL_WRITES.stream().anyMatch(pattern -> pattern.matcher(candidate).matches())) {
                return Access.INTERNAL;
            }
            return Access.WRITE;
        }
        return Access.READ;
    }

    /**
     * @param scopes  the credential's scopes
     * @param session true for a person signed in, false for an API key
     */
    public static boolean allows(Access access, Set<String> scopes, boolean session) {
        // An administrator can do everything an ordinary caller can, except what
        // only services may do and what only a person may do.
        boolean admin = scopes.contains(ApiKey.Scopes.PLATFORM_ADMIN);
        return switch (access) {
            case PUBLIC, AUTHENTICATED -> true;
            case READ -> admin || scopes.contains(ApiKey.Scopes.JOBS_READ) || scopes.contains(ApiKey.Scopes.JOBS_WRITE);
            case WRITE -> admin || scopes.contains(ApiKey.Scopes.JOBS_WRITE);
            case ORG_ADMIN -> admin || scopes.contains(ApiKey.Scopes.ORG_ADMIN);
            case ADMIN -> admin;
            case SESSION -> session;
            case SESSION_WRITE -> session && (admin || scopes.contains(ApiKey.Scopes.JOBS_WRITE));
            case INTERNAL -> false;
        };
    }

    /** Why a credential was refused, for the 403 message. */
    public static String refusal(Access access) {
        return switch (access) {
            case READ -> "This credential does not carry the jobs:read scope.";
            case WRITE -> "This credential does not carry the jobs:write scope.";
            case ORG_ADMIN -> "This requires an organisation admin.";
            case ADMIN -> "This credential does not carry the platform:admin scope.";
            case SESSION -> "This requires signing in with GitHub; an API key can't do it.";
            case SESSION_WRITE -> "This requires signing in with GitHub, with a role that can make changes.";
            case INTERNAL -> "This route is for internal services only.";
            case PUBLIC, AUTHENTICATED -> "Not allowed.";
        };
    }
}
