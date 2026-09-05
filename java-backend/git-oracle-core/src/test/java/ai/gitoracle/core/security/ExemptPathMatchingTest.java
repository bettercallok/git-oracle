package ai.gitoracle.core.security;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L5: the auth-exempt path list was tested with a bare {@code startsWith}, so
 * {@code /actuatorXYZ} — or any route an attacker could get mapped beginning
 * with an exempt string — skipped authentication entirely.
 *
 * <p>The bug itself is already fixed: every filter now matches on a full path
 * segment ({@code equals(p) || startsWith(p + "/")}) and additionally requires
 * the raw path to be canonical, which landed with the H1 and H3 work. What was
 * never written is a test, so nothing stops the cheaper-looking form being
 * reintroduced.
 *
 * <p>This pins the RULE rather than any one filter's copy of it. The five
 * services each hold their own {@code EXEMPT_PATHS} check — they cannot share
 * one, because {@code git-oracle-core} has no servlet API on its classpath and
 * the gateway is WebFlux — so a single shared unit test cannot cover them
 * directly. It can cover the semantics they must all implement.
 */
class ExemptPathMatchingTest {

    private static final List<String> EXEMPT_PATHS = List.of("/actuator", "/health", "/api/v1/health");

    /** The rule every filter implements. */
    private static boolean isExempt(String path) {
        if (!RequestPaths.isCanonical(path)) return false;
        return EXEMPT_PATHS.stream().anyMatch(p -> path.equals(p) || path.startsWith(p + "/"));
    }

    /** The rule they used to implement. Kept to show the difference is real. */
    private static boolean isExemptOldBuggyForm(String path) {
        return EXEMPT_PATHS.stream().anyMatch(path::startsWith);
    }

    @Test
    void genuinelyExemptPathsStillBypassAuth() {
        assertThat(isExempt("/actuator")).isTrue();
        assertThat(isExempt("/actuator/health")).isTrue();
        assertThat(isExempt("/actuator/prometheus")).isTrue();
        assertThat(isExempt("/health")).isTrue();
        assertThat(isExempt("/api/v1/health")).isTrue();
    }

    @Test
    void aPathThatMerelyStartsWithAnExemptStringIsNotExempt() {
        for (String path : List.of(
                "/actuatorXYZ",
                "/actuator-admin",
                "/healthcheck-bypass",
                "/api/v1/healthz",
                "/healthy")) {
            assertThat(isExempt(path))
                .as("%s must NOT skip authentication", path)
                .isFalse();
            // The old form let every one of these through — that is the finding.
            assertThat(isExemptOldBuggyForm(path))
                .as("%s demonstrates the old bug", path)
                .isTrue();
        }
    }

    @Test
    void ordinaryApiRoutesAreNeverExempt() {
        assertThat(isExempt("/api/v1/jobs")).isFalse();
        assertThat(isExempt("/api/v1/admin/tenants")).isFalse();
        assertThat(isExempt("/webhook/github")).isFalse();
    }

    @Test
    void anExemptLookingPrefixThatEscapesUpwardIsNotExempt() {
        // Why the canonical check is part of the rule and not an extra: this
        // starts with "/actuator" but resolves to an admin route.
        assertThat(isExempt("/actuator/../api/v1/admin/tenants")).isFalse();
    }

    @Test
    void encodedOrParameterisedExemptPathsAreNotExempt() {
        // Exempting is a GRANT, so anything ambiguous must fall through to
        // authentication rather than be normalised into an exemption.
        assertThat(isExempt("/actuator/%2e%2e/api/v1/admin")).isFalse();
        assertThat(isExempt("/actuator;x=1/health")).isFalse();
        assertThat(isExempt("/actuator//health")).isFalse();
        assertThat(isExempt("/%61ctuator/health")).isFalse();
    }
}
