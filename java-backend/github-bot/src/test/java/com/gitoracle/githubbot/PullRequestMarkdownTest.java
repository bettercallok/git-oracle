package com.gitoracle.githubbot;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The PR description reports the job's own numbers. Job 9e09ccf6's PR said
 * "Causal Confidence: 95%" (its investigator scored 0.90), "Fix Quality: 50%"
 * (every test passed), "Attempts: 1/5" (the fixer stops at 3), "1/1 passed"
 * (six tests ran) and "null" under Root Cause.
 */
class PullRequestMarkdownTest {

    private static PullRequestRequest request() {
        PullRequestRequest req = new PullRequestRequest();
        req.setJobId("9e09ccf6-55b8-4b31-99b7-decbaa049ec3");
        req.setRootCommit("74d32cc9301280c7e8371872f788fbe7fbf8fe5a");
        req.setCausalScore(0.9);
        req.setFixStrategy("patch");
        req.setFixAttempts(1);
        req.setTestsPassed(6);
        req.setTestsTotal(6);
        req.setQualityScore(1.0);
        req.setTokenBudgetUsed(5365);
        req.setCausalChainNarrative("The NPE comes from an unchecked user.");
        return req;
    }

    @Test
    void theHeaderShowsTheJobsRealNumbers() {
        String md = GitHubController.generateMarkdown(request());

        assertThat(md).contains("**Causal Confidence:** 90%")
            .contains("**Fix Quality:** 100%")
            .contains("**Attempts:** 1/3")
            .contains("✅ 6/6 passed")
            .contains("### Root Cause\nThe NPE comes from an unchecked user.")
            .contains("\"causal_score\": 0.90")
            .contains("\"tests_total\": 6")
            .contains("\"token_budget_used\": 5365")
            .contains("\"tests_verified\": true")
            .doesNotContain("null\n");
    }

    @Test
    void coverageIsNotPrintedWhenNothingMeasuredIt() {
        String md = GitHubController.generateMarkdown(request());

        assertThat(md).doesNotContain("Coverage").doesNotContain("coverage_delta");
    }

    @Test
    void aFixNoTestSuiteRanAgainstSaysItWasNotVerified() {
        // The test runner's safe-pass: allPassed=true, verified=false, qualityScore=1.0.
        PullRequestRequest req = request();
        req.setTestsVerified(false);
        req.setTestsPassed(null);
        req.setTestsTotal(null);

        String md = GitHubController.generateMarkdown(req);

        assertThat(md).contains("### Test Results\n⚠️ No test suite ran — this change was not verified by tests.")
            .contains("**Fix Quality:** n/a")
            .contains("\"tests_verified\": false")
            .contains("\"quality_score\": null")
            .doesNotContain("✅")
            .doesNotContain("100%")
            .doesNotContain("Coverage");
    }

    @Test
    void aSenderThatDoesNotSayIsTreatedAsVerified() {
        PullRequestRequest req = request();
        req.setTestsVerified(null);

        assertThat(GitHubController.generateMarkdown(req)).contains("✅ 6/6 passed").contains("**Fix Quality:** 100%");
    }

    @Test
    void aDirectFixWithNoInvestigationOrTestCountsSaysSoInsteadOfInventingNumbers() {
        PullRequestRequest req = request();
        req.setCausalScore(null);
        req.setRootCommit("unknown");
        req.setTestsPassed(null);
        req.setTestsTotal(null);
        req.setCausalChainNarrative(null);

        String md = GitHubController.generateMarkdown(req);

        assertThat(md).contains("**Root Commit:** n/a")
            .contains("**Causal Confidence:** n/a")
            .contains("_Not investigated — this fix was requested directly._")
            .contains("### Test Results\n✅ Passed\n")
            .contains("\"causal_score\": null")
            .contains("\"root_commit\": null")
            .contains("\"tests_passed\": null");
    }

    @Test
    void theMetadataBlockIsValidJsonWithOrWithoutValues() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        PullRequestRequest bare = request();
        bare.setCausalScore(null);
        bare.setRootCommit(null);
        bare.setTestsPassed(null);
        bare.setTestsTotal(null);

        PullRequestRequest unverified = request();
        unverified.setTestsVerified(false);
        unverified.setQualityScore(null);
        unverified.setCoverageDelta(0.012);

        for (PullRequestRequest req : new PullRequestRequest[] {request(), bare, unverified}) {
            String md = GitHubController.generateMarkdown(req);
            String json = md.substring(md.indexOf("{"), md.indexOf("}") + 1);
            var metadata = mapper.readTree(json);

            assertThat(metadata.get("job_id").asText()).isEqualTo("9e09ccf6-55b8-4b31-99b7-decbaa049ec3");
            assertThat(metadata.get("token_budget_used").asInt()).isEqualTo(5365);
        }
        // The error-ingestor finds a PR's job from this marker.
        assertThat(GitHubController.generateMarkdown(request()))
            .startsWith("<!-- gitoracle:job_id:9e09ccf6-55b8-4b31-99b7-decbaa049ec3 -->");
    }
}
