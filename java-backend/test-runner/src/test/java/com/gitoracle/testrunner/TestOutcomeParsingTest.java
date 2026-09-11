package com.gitoracle.testrunner;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L1: the pass/fail verdict is derived from the stdout of the repository under
 * test — which is the untrusted third-party code being evaluated. A bare
 * {@code output.contains("no tests ran")} matched that string ANYWHERE in
 * megabytes of build output: in a source listing echoed by the build, in a
 * dependency's log line, or printed deliberately by a repo that wanted a pass.
 *
 * These pin the anchoring. They do NOT claim the result is trustworthy — see
 * computeQualityScore's javadoc: nothing parsed out of attacker-controlled
 * output can be, and switching to --junitxml would not change that, because the
 * report file is written by the same untrusted process. What anchoring removes
 * is the accidental match and the one-line version of the trick.
 */
class TestOutcomeParsingTest {

    // ── Real framework summaries must still be recognised ───────────────────

    @Test
    void recognisesAPytestNoTestsSummary() {
        String output = "============================= test session starts ==============================\n"
                      + "collected 0 items\n"
                      + "============================ no tests ran in 0.01s =============================";

        assertThat(TestRunnerController.hasSummaryLineContaining(output, "no tests ran")).isTrue();
        assertThat(TestRunnerController.hasSummaryLineContaining(output, "collected 0 items")).isTrue();
    }

    // ── The spoofing that used to work ──────────────────────────────────────

    @Test
    void aRepoEchoingTheMagicStringNoLongerCounts() {
        // `echo "no tests ran"` in a repo's own test command was enough to turn
        // a failing run into a safe-pass.
        String output = "Running build...\n"
                      + "no tests ran\n"
                      + "FAILED: 3 assertions did not hold";

        assertThat(TestRunnerController.hasSummaryLineContaining(output, "no tests ran")).isFalse();
    }

    @Test
    void theStringAppearingInBuildChatterDoesNotCount() {
        String output = "[INFO] Compiling src/main/java/Foo.java\n"
                      + "[INFO] note: the docs say \"collected 0 items\" means an empty suite\n"
                      + "[INFO] BUILD SUCCESS";

        assertThat(TestRunnerController.hasSummaryLineContaining(output, "collected 0 items")).isFalse();
    }

    @Test
    void aSourceListingContainingTheStringDoesNotCount() {
        // Build output frequently echoes source lines.
        String output = "  12 |     print(\"no tests ran\")\n"
                      + "  13 |     return 0";

        assertThat(TestRunnerController.hasSummaryLineContaining(output, "no tests ran")).isFalse();
    }

    @Test
    void emptyAndNullOutputAreHandled() {
        assertThat(TestRunnerController.hasSummaryLineContaining(null, "no tests ran")).isFalse();
        assertThat(TestRunnerController.hasSummaryLineContaining("", "no tests ran")).isFalse();
    }

    // ── TestResult carries the verified/unverified distinction ──────────────

    @Test
    void aTestResultIsVerifiedByDefault() {
        // The 4-arg constructor is used by every real pass/fail path, so the
        // default must be the honest one for those.
        assertThat(new TestResult(true, 1.0, 0.0, "3 passed").isVerified()).isTrue();
    }

    @Test
    void anUnverifiedResultStillReportsAllPassed() {
        // Deliberate: flipping allPassed would stop every no-test repo
        // mid-pipeline. The distinction is carried alongside it, not instead.
        TestResult unverified = new TestResult(true, 1.0, 0.0, "no suite", false);

        assertThat(unverified.isAllPassed()).isTrue();
        assertThat(unverified.isVerified()).isFalse();
    }
}
