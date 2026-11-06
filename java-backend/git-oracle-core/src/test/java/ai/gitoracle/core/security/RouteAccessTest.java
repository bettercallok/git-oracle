package ai.gitoracle.core.security;

import ai.gitoracle.core.security.RouteAccess.Access;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class RouteAccessTest {

    private static final Set<String> READ = Set.of("jobs:read");
    private static final Set<String> READ_WRITE = Set.of("jobs:read", "jobs:write");
    private static final Set<String> ADMIN = Set.of("platform:admin");

    @Test
    void anUnknownRouteGetsTheStrictDefault() {
        assertThat(RouteAccess.resolve("GET", "/api/v1/brand-new")).isEqualTo(Access.READ);
        assertThat(RouteAccess.resolve("POST", "/api/v1/brand-new")).isEqualTo(Access.WRITE);
        assertThat(RouteAccess.resolve("PATCH", "/api/v1/brand-new")).isEqualTo(Access.WRITE);
        assertThat(RouteAccess.resolve("TRACE", "/api/v1/brand-new")).isEqualTo(Access.WRITE);
    }

    @Test
    void encodingAndPathTricksCantChangeTheAnswer() {
        for (String path : new String[] {
            "/api/v1/admin;x=1/tenants", "/api/v1/%61dmin/tenants", "/api/v1/jobs/../admin/tenants", "//api/v1/admin/tenants"
        }) {
            assertThat(RouteAccess.resolve("GET", path)).as(path).isEqualTo(Access.ADMIN);
        }
        assertThat(RouteAccess.resolve("POST", "/api/v1/jobs/1/events/")).isEqualTo(Access.INTERNAL);
        assertThat(RouteAccess.resolve("POST", "/api/v1/jobs/1;x=1/events")).isEqualTo(Access.INTERNAL);
        assertThat(RouteAccess.resolve("POST", "/api/v1/budget/1/%72ecord")).isEqualTo(Access.INTERNAL);
    }

    @Test
    void aSimilarNameIsntTreatedAsTheProtectedPath() {
        assertThat(RouteAccess.resolve("GET", "/api/v1/adminXYZ")).isEqualTo(Access.READ);
        assertThat(RouteAccess.resolve("GET", "/api/v1/keysXYZ")).isEqualTo(Access.READ);
    }

    @Test
    void writingToASignInPathIsNotPublic() {
        assertThat(RouteAccess.resolve("POST", "/api/v1/auth/github/login")).isEqualTo(Access.WRITE);
    }

    @Test
    void readOnlyCredentialsCanOnlyRead() {
        assertThat(RouteAccess.allows(Access.READ, READ, false)).isTrue();
        assertThat(RouteAccess.allows(Access.WRITE, READ, false)).isFalse();
        assertThat(RouteAccess.allows(Access.ADMIN, READ_WRITE, false)).isFalse();
    }

    @Test
    void aWriteKeyCanReadEvenWithoutTheReadScope() {
        assertThat(RouteAccess.allows(Access.READ, Set.of("jobs:write"), false)).isTrue();
    }

    @Test
    void aCredentialWithNoScopesGetsNothingButItsIdentity() {
        assertThat(RouteAccess.allows(Access.READ, Set.of(), false)).isFalse();
        assertThat(RouteAccess.allows(Access.WRITE, Set.of(), false)).isFalse();
        assertThat(RouteAccess.allows(Access.AUTHENTICATED, Set.of(), false)).isTrue();
    }

    @Test
    void adminImpliesReadAndWrite() {
        assertThat(RouteAccess.allows(Access.READ, ADMIN, false)).isTrue();
        assertThat(RouteAccess.allows(Access.WRITE, ADMIN, false)).isTrue();
        assertThat(RouteAccess.allows(Access.ADMIN, ADMIN, false)).isTrue();
    }

    @Test
    void onlyAPersonCanManageKeysEvenAnAdminKeyCant() {
        assertThat(RouteAccess.allows(Access.SESSION, ADMIN, false)).isFalse();
        assertThat(RouteAccess.allows(Access.SESSION, READ, true)).isTrue();
        assertThat(RouteAccess.allows(Access.SESSION_WRITE, ADMIN, false)).isFalse();
    }

    @Test
    void nobodyGetsInternalRoutesThroughTheGateway() {
        assertThat(RouteAccess.allows(Access.INTERNAL, ADMIN, true)).isFalse();
    }

    @Test
    void aViewerCanListKeysButNotCreateThem() {
        assertThat(RouteAccess.resolve("GET", "/api/v1/keys")).isEqualTo(Access.SESSION);
        assertThat(RouteAccess.resolve("POST", "/api/v1/keys")).isEqualTo(Access.SESSION_WRITE);
        assertThat(RouteAccess.allows(Access.SESSION_WRITE, READ, true)).isFalse();
        assertThat(RouteAccess.allows(Access.SESSION_WRITE, READ_WRITE, true)).isTrue();
    }

    @Test
    void organisationManagementNeedsAnOrgAdminWhoStillCantAdministerThePlatform() {
        Set<String> orgAdmin = Set.of("jobs:read", "jobs:write", "org:admin");

        assertThat(RouteAccess.allows(RouteAccess.resolve("POST", "/api/v1/org/invitations"), orgAdmin, true)).isTrue();
        assertThat(RouteAccess.allows(RouteAccess.resolve("POST", "/api/v1/org/invitations"), READ_WRITE, true)).isFalse();
        assertThat(RouteAccess.allows(RouteAccess.resolve("GET", "/api/v1/org/audit"), READ_WRITE, true)).isFalse();
        assertThat(RouteAccess.allows(RouteAccess.resolve("GET", "/api/v1/org/members"), READ, true)).isTrue();
        assertThat(RouteAccess.allows(RouteAccess.resolve("GET", "/api/v1/admin/repos"), orgAdmin, true)).isFalse();
        assertThat(RouteAccess.allows(RouteAccess.resolve("GET", "/api/v1/orgXYZ"), READ, true)).isTrue();
    }

    @Test
    void onlyAnOrgAdminCanPublishEvalResults() {
        assertThat(RouteAccess.resolve("POST", "/api/v1/evals")).isEqualTo(Access.ORG_ADMIN);
        assertThat(RouteAccess.resolve("GET", "/api/v1/evals")).isEqualTo(Access.READ);
    }
}
