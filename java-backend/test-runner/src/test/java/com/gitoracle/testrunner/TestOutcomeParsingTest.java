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

    // ── Test counts and the quality score ───────────────────────────────────
    //
    // Real Surefire output, from bettercallok/chillcall run with the pinned
    // maven image: main (6 tests, all green) and case/npe (one seeded failure).

    private static final String MAVEN_GREEN = String.join("\n",
        "[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.031 s -- in com.example.UserServiceTest",
        "[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.003 s -- in com.example.HttpClientConfigTest",
        "[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.003 s -- in com.example.BatchProcessorTest",
        "[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.003 s -- in com.example.RateLimiterTest",
        "[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.003 s -- in com.example.StatsTest",
        "[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.002 s -- in com.example.AuthServiceTest",
        "[INFO] ",
        "[INFO] Results:",
        "[INFO] ",
        "[INFO] Tests run: 6, Failures: 0, Errors: 0, Skipped: 0",
        "[INFO] ",
        "[INFO] BUILD SUCCESS");

    private static final String MAVEN_ONE_FAILURE = String.join("\n",
        "[ERROR] Tests run: 1, Failures: 1, Errors: 0, Skipped: 0, Time elapsed: 0.045 s <<< FAILURE! -- in com.example.UserServiceTest",
        "[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.005 s -- in com.example.HttpClientConfigTest",
        "[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.003 s -- in com.example.BatchProcessorTest",
        "[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.002 s -- in com.example.RateLimiterTest",
        "[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.002 s -- in com.example.StatsTest",
        "[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.003 s -- in com.example.AuthServiceTest",
        "[ERROR] Failures: ",
        "[ERROR]   UserServiceTest.processPayment_withNullUser_doesNotThrowRawNPE:17 processPayment must not ...",
        "[ERROR] Tests run: 6, Failures: 1, Errors: 0, Skipped: 0");

    @Test
    void aGreenMavenRunIsScoredFromItsRunTotalNotTheFirstTestClass() {
        TestRunnerController.TestCounts counts = TestRunnerController.parseTestCounts(MAVEN_GREEN);

        assertThat(counts).isEqualTo(new TestRunnerController.TestCounts(6, 0));
        assertThat(TestRunnerController.qualityScore(counts, true)).isEqualTo(1.0);
    }

    @Test
    void aMavenFailureLowersTheScoreByTheShareOfTestsThatFailed() {
        TestRunnerController.TestCounts counts = TestRunnerController.parseTestCounts(MAVEN_ONE_FAILURE);

        assertThat(counts).isEqualTo(new TestRunnerController.TestCounts(6, 1));
        assertThat(TestRunnerController.qualityScore(counts, false)).isEqualTo(5.0 / 6);
    }

    @Test
    void mavenErrorsCountAsFailures() {
        TestRunnerController.TestCounts counts =
            TestRunnerController.parseTestCounts("[ERROR] Tests run: 4, Failures: 1, Errors: 2, Skipped: 0");

        assertThat(counts).isEqualTo(new TestRunnerController.TestCounts(4, 3));
    }

    @Test
    void aGreenRunWithNoRecognisableSummaryScoresByItsExitCodeNotAHalf() {
        // What `mvn test -q` printed for chillcall's green run: only the tests'
        // own stdout. It used to score 0.5 — "Fix Quality: 50%" on every PR.
        assertThat(TestRunnerController.parseTestCounts("a\nb\nc")).isNull();
        assertThat(TestRunnerController.qualityScore(null, true)).isEqualTo(1.0);
        assertThat(TestRunnerController.qualityScore(null, false)).isEqualTo(0.0);
    }

    @Test
    void pytestPassedFailedAndErrorsAreAllCounted() {
        String output = "collected 6 items\n"
            + "tests/test_x.py::test_a PASSED\n"
            + "=========== 3 passed, 2 failed, 1 error in 0.42s ===========";

        assertThat(TestRunnerController.parseTestCounts(output)).isEqualTo(new TestRunnerController.TestCounts(6, 3));
    }

    @Test
    void jestAndCargoSummariesAreRead() {
        assertThat(TestRunnerController.parseTestCounts("Tests:       1 failed, 4 passed, 5 total"))
            .isEqualTo(new TestRunnerController.TestCounts(5, 1));
        // cargo prints one result per test binary; they add up.
        assertThat(TestRunnerController.parseTestCounts(
                "test result: ok. 3 passed; 0 failed; 0 ignored; 0 measured\n"
                + "test result: FAILED. 1 passed; 1 failed; 0 ignored; 0 measured"))
            .isEqualTo(new TestRunnerController.TestCounts(5, 1));
    }

    @Test
    void countsAnywhereButASummaryLineAreIgnored() {
        // The old parser fell back to searching the whole output when it found
        // no summary line, so a repo printing this scored itself.
        String output = "System.out: 99 passed\n"
            + "log: Tests run: 99, Failures: 0, Errors: 0, Skipped: 0 (from a fixture file)";

        assertThat(TestRunnerController.parseTestCounts(output)).isNull();
    }

    @Test
    void aTestResultCarriesItsCounts() {
        TestResult result = new TestResult(true, 1.0, 0.0, "")
            .withCounts(new TestRunnerController.TestCounts(6, 0));

        assertThat(result.getTestsTotal()).isEqualTo(6);
        assertThat(result.getTestsFailed()).isZero();
        assertThat(new TestResult(true, 1.0, 0.0, "").withCounts(null).getTestsTotal()).isNull();
    }
}
