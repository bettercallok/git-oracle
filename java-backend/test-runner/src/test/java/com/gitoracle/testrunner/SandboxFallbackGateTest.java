package com.gitoracle.testrunner;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression coverage for the fix removing test-runner's unsandboxed native
 * fallback (runNative -> deleted; a gated runNativeUnsafe replaces it).
 *
 * TestRunnerController has no constructor-injectable seams for its @Value
 * fields or its docker-availability flag, so these tests set them directly
 * via reflection rather than standing up a full Spring context (slow, and
 * would make Docker's real availability on the test machine part of the
 * test's own behaviour, which is exactly what must NOT be true here).
 */
class SandboxFallbackGateTest {

    private TestRunnerController controller;

    @BeforeEach
    void setUp() {
        controller = new TestRunnerController();
    }

    private void setField(String name, Object value) throws Exception {
        Field f = TestRunnerController.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(controller, value);
    }

    private boolean invokeIsAuthorized(String repoUrl) throws Exception {
        Method m = TestRunnerController.class.getDeclaredMethod("isNativeFallbackAuthorized", String.class);
        m.setAccessible(true);
        return (boolean) m.invoke(controller, repoUrl);
    }

    // ─── isNativeFallbackAuthorized: the actual security decision ────────────

    @Test
    void deniesByDefault_flagOffAndNoAllowlist() throws Exception {
        setField("allowUnsafeNativeTests", false);
        setField("trustedRepos", java.util.Set.of());
        assertThat(invokeIsAuthorized("https://github.com/bettercallok/git-oracle")).isFalse();
    }

    @Test
    void emptyAllowlistDeniesEvenWithFlagOn() throws Exception {
        // Mirrors guardrails' own convention: an unset allowlist must never
        // silently authorize every repo just because the flag is set.
        setField("allowUnsafeNativeTests", true);
        setField("trustedRepos", java.util.Set.of());
        assertThat(invokeIsAuthorized("https://github.com/bettercallok/git-oracle")).isFalse();
    }

    @Test
    void flagOnAndRepoOnAllowlist_isAuthorized() throws Exception {
        setField("allowUnsafeNativeTests", true);
        setField("trustedRepos", java.util.Set.of("https://github.com/bettercallok/git-oracle"));
        assertThat(invokeIsAuthorized("https://github.com/bettercallok/git-oracle")).isTrue();
    }

    @Test
    void flagOnButRepoNotOnAllowlist_isDenied() throws Exception {
        setField("allowUnsafeNativeTests", true);
        setField("trustedRepos", java.util.Set.of("https://github.com/bettercallok/git-oracle"));
        assertThat(invokeIsAuthorized("https://github.com/some-other-org/some-other-repo")).isFalse();
    }

    @Test
    void allowlistSetButFlagOff_isDenied() throws Exception {
        setField("allowUnsafeNativeTests", false);
        setField("trustedRepos", java.util.Set.of("https://github.com/bettercallok/git-oracle"));
        assertThat(invokeIsAuthorized("https://github.com/bettercallok/git-oracle")).isFalse();
    }

    @Test
    void nullRepoUrlIsNeverAuthorized() throws Exception {
        setField("allowUnsafeNativeTests", true);
        setField("trustedRepos", java.util.Set.of("https://github.com/bettercallok/git-oracle"));
        assertThat(invokeIsAuthorized(null)).isFalse();
    }

    // ─── /test endpoint: fails closed (503) when the sandbox can't be used ────

    @Test
    void refusesRequestWith503WhenDockerUnavailableAndNoFallbackAuthorized() throws Exception {
        setField("dockerAvailable", false);
        setField("allowUnsafeNativeTests", false);
        setField("trustedRepos", java.util.Set.of());

        TestRequest request = new TestRequest();
        request.setJobId("sandbox-gate-test");
        request.setRepoUrl("https://github.com/bettercallok/git-oracle");

        ResponseEntity<TestResult> response = controller.runTest(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    void doesNotReturn503WhenDockerAvailable() throws Exception {
        setField("dockerAvailable", true);
        setField("allowUnsafeNativeTests", false);
        setField("trustedRepos", java.util.Set.of());

        TestRequest request = new TestRequest();
        request.setJobId("sandbox-gate-test-2");
        // Deliberately no repoUrl/repoPath so this hits the safe-pass branch
        // immediately (200) rather than attempting a real network clone —
        // this test only needs to prove the 503 guard does not fire when
        // Docker is available, not exercise the full clone/test pipeline.
        ResponseEntity<TestResult> response = controller.runTest(request);

        assertThat(response.getStatusCode()).isNotEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }
}
