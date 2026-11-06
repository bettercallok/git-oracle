package ai.gitoracle.orchestrator.security;

import ai.gitoracle.core.security.RouteAccess;
import ai.gitoracle.core.security.RouteAccess.Access;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static java.util.Map.entry;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every route in this service, with the access it must have, checked for every
 * kind of caller. Adding a route fails this test until it's listed here, which is
 * the point: someone has to decide who may call it.
 */
class RouteAccessMatrixTest {

    private static final Map<String, Access> EXPECTED = Map.ofEntries(
        entry("GET /api/v1/auth/config", Access.PUBLIC),
        entry("GET /api/v1/auth/github/login", Access.PUBLIC),
        entry("GET /api/v1/auth/github/callback", Access.PUBLIC),
        entry("GET /api/v1/auth/me", Access.AUTHENTICATED),
        entry("POST /api/v1/auth/logout", Access.AUTHENTICATED),

        entry("GET /api/v1/repos", Access.READ),
        entry("GET /api/v1/jobs", Access.READ),
        entry("GET /api/v1/jobs/stats", Access.READ),
        entry("GET /api/v1/jobs/{id}", Access.READ),
        entry("GET /api/v1/jobs/{id}/events", Access.READ),
        entry("GET /api/v1/jobs/{id}/trace", Access.READ),
        entry("GET /api/v1/traces", Access.READ),
        entry("GET /api/v1/escalations", Access.READ),
        entry("GET /api/v1/evals", Access.READ),
        entry("GET /api/v1/pr-outcomes/stats", Access.READ),
        entry("GET /api/v1/prompts/stats", Access.READ),
        entry("GET /api/v1/budget/{jobId}", Access.READ),
        entry("GET /api/v1/commits", Access.READ),
        entry("GET /api/v1/commits/{sha}/diff", Access.READ),

        entry("POST /api/v1/jobs", Access.WRITE),
        entry("POST /api/v1/trigger", Access.WRITE),
        entry("POST /api/v1/jobs/{jobId}/feedback", Access.WRITE),
        entry("POST /api/v1/escalations/{id}/resolve", Access.WRITE),
        entry("POST /api/v1/evals", Access.WRITE),
        // Reading a commit, but it spends LLM tokens.
        entry("POST /api/v1/commits/{sha}/analyze", Access.WRITE),

        entry("GET /api/v1/keys", Access.SESSION),
        entry("POST /api/v1/keys", Access.SESSION),
        entry("DELETE /api/v1/keys/{keyPrefix}", Access.SESSION),

        entry("POST /api/v1/jobs/{id}/events", Access.INTERNAL),
        entry("POST /api/v1/jobs/{jobId}/prompt-version", Access.INTERNAL),
        entry("POST /api/v1/budget/{jobId}/record", Access.INTERNAL),

        entry("POST /api/v1/admin/tenants", Access.ADMIN),
        entry("GET /api/v1/admin/tenants/{id}/metrics", Access.ADMIN),
        entry("POST /api/v1/admin/tenants/{id}/sentry-secret", Access.ADMIN),
        entry("POST /api/v1/admin/api-keys", Access.ADMIN),
        entry("GET /api/v1/admin/api-keys", Access.ADMIN),
        entry("DELETE /api/v1/admin/api-keys/{keyPrefix}", Access.ADMIN),
        entry("GET /api/v1/admin/repos", Access.ADMIN),
        entry("POST /api/v1/admin/repos", Access.ADMIN),
        entry("DELETE /api/v1/admin/repos", Access.ADMIN),
        entry("GET /api/v1/admin/system/status", Access.ADMIN)
    );

    /** Who can call what. A key is {session=false}; a person signed in is {session=true}. */
    private record Caller(String name, Set<String> scopes, boolean session) {}

    private static final Caller READ_KEY = new Caller("read-only key", Set.of("jobs:read"), false);
    private static final Caller WRITE_KEY = new Caller("read-write key", Set.of("jobs:read", "jobs:write"), false);
    private static final Caller ADMIN_KEY = new Caller("admin key", Set.of("platform:admin", "jobs:read", "jobs:write"), false);
    private static final Caller MEMBER = new Caller("signed-in member", Set.of("jobs:read", "jobs:write"), true);
    private static final Caller ADMIN = new Caller("signed-in admin", Set.of("platform:admin", "jobs:read", "jobs:write"), true);

    private static final Map<Caller, Set<Access>> ALLOWED = new LinkedHashMap<>();
    static {
        ALLOWED.put(READ_KEY, Set.of(Access.PUBLIC, Access.AUTHENTICATED, Access.READ));
        ALLOWED.put(WRITE_KEY, Set.of(Access.PUBLIC, Access.AUTHENTICATED, Access.READ, Access.WRITE));
        ALLOWED.put(ADMIN_KEY, Set.of(Access.PUBLIC, Access.AUTHENTICATED, Access.READ, Access.WRITE, Access.ADMIN));
        ALLOWED.put(MEMBER, Set.of(Access.PUBLIC, Access.AUTHENTICATED, Access.READ, Access.WRITE, Access.SESSION));
        ALLOWED.put(ADMIN, Set.of(Access.PUBLIC, Access.AUTHENTICATED, Access.READ, Access.WRITE, Access.ADMIN, Access.SESSION));
    }

    private static Set<String> routesInThisService() throws ClassNotFoundException {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));

        Set<String> routes = new TreeSet<>();
        for (BeanDefinition bean : scanner.findCandidateComponents("ai.gitoracle.orchestrator")) {
            Class<?> controller = Class.forName(bean.getBeanClassName());
            RequestMapping root = AnnotatedElementUtils.findMergedAnnotation(controller, RequestMapping.class);
            String[] bases = root == null || root.path().length == 0 ? new String[] {""} : root.path();
            for (Method method : controller.getDeclaredMethods()) {
                RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
                if (mapping == null) continue;
                String[] paths = mapping.path().length == 0 ? new String[] {""} : mapping.path();
                for (String base : bases) {
                    for (String path : paths) {
                        for (var verb : mapping.method()) {
                            routes.add(verb.name() + " " + base + path);
                        }
                    }
                }
            }
        }
        return routes;
    }

    private static Access resolve(String route) {
        String[] parts = route.split(" ");
        return RouteAccess.resolve(parts[0], parts[1].replaceAll("\\{[^}]+}", "sample-value"));
    }

    @Test
    void everyRouteIsClassifiedAndNothingIsListedThatDoesntExist() throws Exception {
        assertThat(routesInThisService()).containsExactlyInAnyOrderElementsOf(new TreeSet<>(EXPECTED.keySet()));
    }

    @Test
    void everyRouteRequiresWhatThisTableSays() {
        EXPECTED.forEach((route, access) -> assertThat(resolve(route)).as(route).isEqualTo(access));
    }

    @Test
    void everyCallerGetsExactlyTheRoutesTheyShould() {
        ALLOWED.forEach((caller, allowed) -> EXPECTED.forEach((route, access) ->
            assertThat(RouteAccess.allows(access, caller.scopes(), caller.session()))
                .as("%s calling %s", caller.name(), route)
                .isEqualTo(allowed.contains(access))));
    }

    @Test
    void aReadOnlyKeyCanStartNothing() {
        EXPECTED.forEach((route, access) -> {
            if (!route.startsWith("GET ") && !route.equals("POST /api/v1/auth/logout")) {
                assertThat(RouteAccess.allows(access, READ_KEY.scopes(), false)).as(route).isFalse();
            }
        });
    }
}
