package ai.gitoracle.core.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two entries marked LIVE BYPASS below are not hypothetical: both were
 * confirmed against running services to reach AdminController and create a
 * tenant with no platform:admin scope, one of them through the gateway using an
 * ordinary tenant-scoped API key.
 */
class RequestPathsTest {

    private static final String ADMIN = "/api/v1/admin";

    // ── The bypasses this class exists to close ─────────────────────────────

    @Test
    void percentEncodedAdminSegmentIsRecognised() {
        // LIVE BYPASS: %61 is 'a'. The filter tested the raw string and did not
        // match; Spring decoded it and dispatched to AdminController.
        assertThat(RequestPaths.isUnderPrefix("/api/v1/%61dmin/tenants", ADMIN)).isTrue();
        assertThat(RequestPaths.isUnderPrefix("/api/v1/%61%64%6din/tenants", ADMIN)).isTrue();
    }

    @Test
    void pathParametersAreStrippedBeforeMatching() {
        // LIVE BYPASS: Tomcat strips ";x=1" before mapping; the filter did not.
        assertThat(RequestPaths.isUnderPrefix("/api/v1/admin;x=1/tenants", ADMIN)).isTrue();
        assertThat(RequestPaths.isUnderPrefix("/api/v1/admin/tenants;jsessionid=abc", ADMIN)).isTrue();
        assertThat(RequestPaths.isUnderPrefix("/api/v1;a=b/admin;c=d/tenants", ADMIN)).isTrue();
    }

    @Test
    void doubleEncodingStillResolves() {
        // No layer here is known to double-decode, but this feeds a deny
        // decision, so the extra pass costs nothing and fails safe.
        assertThat(RequestPaths.isUnderPrefix("/api/v1/%2561dmin/tenants", ADMIN)).isTrue();
    }

    @Test
    void duplicateSlashesAndDotSegmentsResolve() {
        assertThat(RequestPaths.isUnderPrefix("/api/v1//admin/tenants", ADMIN)).isTrue();
        assertThat(RequestPaths.isUnderPrefix("/api/v1/./admin/tenants", ADMIN)).isTrue();
        assertThat(RequestPaths.isUnderPrefix("/api/v1/foo/../admin/tenants", ADMIN)).isTrue();
        assertThat(RequestPaths.isUnderPrefix("//api//v1//admin//tenants", ADMIN)).isTrue();
    }

    @Test
    void encodedSlashSeparatingTheAdminPrefixIsRecognised() {
        // Tomcat rejects %2F by default, but this must not depend on that.
        assertThat(RequestPaths.isUnderPrefix("/api/v1/admin%2Ftenants", ADMIN)).isTrue();
        assertThat(RequestPaths.isUnderPrefix("/api%2Fv1%2Fadmin%2Ftenants", ADMIN)).isTrue();
    }

    // ── Must not over-match ─────────────────────────────────────────────────

    @Test
    void aRouteThatMerelySharesATextPrefixIsNotUnderIt() {
        assertThat(RequestPaths.isUnderPrefix("/api/v1/adminXYZ", ADMIN)).isFalse();
        assertThat(RequestPaths.isUnderPrefix("/api/v1/administrators", ADMIN)).isFalse();
    }

    @Test
    void ordinaryRoutesAreUnaffected() {
        assertThat(RequestPaths.isUnderPrefix("/api/v1/jobs", ADMIN)).isFalse();
        assertThat(RequestPaths.isUnderPrefix("/api/v1/budget/abc/record", ADMIN)).isFalse();
        assertThat(RequestPaths.isUnderPrefix("/actuator/health", ADMIN)).isFalse();
    }

    @Test
    void theAdminRootItselfMatches() {
        assertThat(RequestPaths.isUnderPrefix("/api/v1/admin", ADMIN)).isTrue();
        assertThat(RequestPaths.isUnderPrefix("/api/v1/admin/", ADMIN)).isTrue();
    }

    // ── canonicalize ────────────────────────────────────────────────────────

    @Test
    void canonicalizeProducesTheRoutersView() {
        assertThat(RequestPaths.canonicalize("/api/v1/%61dmin/tenants")).isEqualTo("/api/v1/admin/tenants");
        assertThat(RequestPaths.canonicalize("/api/v1/admin;x=1/tenants")).isEqualTo("/api/v1/admin/tenants");
        assertThat(RequestPaths.canonicalize("/api/v1//admin/./tenants")).isEqualTo("/api/v1/admin/tenants");
        assertThat(RequestPaths.canonicalize("/api/v1/admin/tenants/")).isEqualTo("/api/v1/admin/tenants");
    }

    @Test
    void canonicalizeHandlesDegenerateInput() {
        assertThat(RequestPaths.canonicalize(null)).isEqualTo("/");
        assertThat(RequestPaths.canonicalize("")).isEqualTo("/");
        assertThat(RequestPaths.canonicalize("/")).isEqualTo("/");
        assertThat(RequestPaths.canonicalize("///")).isEqualTo("/");
        assertThat(RequestPaths.canonicalize("/..")).isEqualTo("/");
        assertThat(RequestPaths.canonicalize("/../../..")).isEqualTo("/");
    }

    @Test
    void aMalformedEscapeDoesNotThrow() {
        // "%zz" is not a valid escape; the router will reject the request, but
        // this must not be the thing that throws first.
        assertThat(RequestPaths.canonicalize("/api/v1/%zz/admin")).isNotNull();
        assertThat(RequestPaths.canonicalize("/api/v1/%")).isNotNull();
    }

    @Test
    void decodingIsBoundedAgainstAPathologicallyNestedEncoding() {
        String nested = "/api/v1/%25%32%35%32%35" + "61dmin";
        assertThat(RequestPaths.canonicalize(nested)).isNotNull();
    }

    // ── isCanonical, for allowlist checks ───────────────────────────────────

    @Test
    void isCanonicalAcceptsOnlyAlreadyNormalisedPaths() {
        assertThat(RequestPaths.isCanonical("/actuator/health")).isTrue();
        assertThat(RequestPaths.isCanonical("/api/v1/health")).isTrue();

        assertThat(RequestPaths.isCanonical("/actuator/%68ealth")).isFalse();
        assertThat(RequestPaths.isCanonical("/actuator;x=1/health")).isFalse();
        assertThat(RequestPaths.isCanonical("/actuator//health")).isFalse();
        assertThat(RequestPaths.isCanonical("/actuator/health/")).isFalse();
        assertThat(RequestPaths.isCanonical(null)).isFalse();
    }

    @Test
    void isCanonicalRejectsAnExemptLookingPrefixThatEscapesUpward() {
        // The reason exempt lists must not canonicalise first: this resolves to
        // an admin path, but its raw prefix looks exempt.
        assertThat(RequestPaths.isCanonical("/actuator/../api/v1/admin/tenants")).isFalse();
        assertThat(RequestPaths.canonicalize("/actuator/../api/v1/admin/tenants"))
            .isEqualTo("/api/v1/admin/tenants");
    }
}
