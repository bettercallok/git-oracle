package com.gitoracle.githubbot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.kohsuke.github.GitHub;

@RestController
public class GitHubController {
    private static final Logger logger = LoggerFactory.getLogger(GitHubController.class);
    
    private final GitHubClient githubClient;
    
    public GitHubController(GitHubClient githubClient) {
        this.githubClient = githubClient;
    }

    @PostMapping("/pull-request")
    public String createPullRequest(@RequestBody PullRequestRequest request) {
        logger.info("Received request to open PR for job: {}", request.getJobId());
        
        try {
            String markdown = generateMarkdown(request);
            logger.info("Generated PR Markdown:\n{}", markdown);
            
            // Connect to GitHub
            GitHub github = githubClient.getAuthenticatedGitHub();
            logger.info("Successfully authenticated with GitHub as app.");
            
            // Note: Since this is just the skeleton to verify auth and markdown generation,
            // we will not actually open a real PR on the user's repo yet to avoid spamming them.
            // But the authentication works!
            logger.info("TODO (Layer 6 Phase 3): Use github.getRepository('{}') to create the branch and PR.", request.getRepoFullName());
            
            return "Successfully authenticated and generated Markdown!";
        } catch (Exception e) {
            logger.error("Failed to process PR request", e);
            return "Error: " + e.getMessage();
        }
    }
    
    private String generateMarkdown(PullRequestRequest req) {
        return String.format(
            "<!-- gitOracle:metadata\n" +
            "{\n" +
            "  \"job_id\": \"%s\",\n" +
            "  \"agent_version\": \"1.0.0\",\n" +
            "  \"root_commit\": \"%s\",\n" +
            "  \"causal_score\": %.2f,\n" +
            "  \"fix_strategy\": \"%s\",\n" +
            "  \"fix_attempts\": %d,\n" +
            "  \"tests_passed\": %d,\n" +
            "  \"tests_total\": %d,\n" +
            "  \"coverage_delta\": %+.2f,\n" +
            "  \"quality_score\": %.2f,\n" +
            "  \"token_budget_used\": %d,\n" +
            "  \"escalated\": false\n" +
            "}\n" +
            "-->\n\n" +
            "## \uD83E\uDD16 GitOracle Autonomous Fix\n\n" +
            "**Root Commit:** `%s` · **Causal Confidence:** %.0f%% · **Fix Quality:** %.0f%% · **Attempts:** %d/5\n\n" +
            "### Root Cause\n%s\n\n" +
            "### What Changed\n%s\n\n" +
            "### Test Results\n✅ %d/%d passed · Coverage %+.2f%%\n\n" +
            "---\n*This PR was generated autonomously by GitOracle. Questions? The Reviewer Agent will respond to your comments.*",
            req.getJobId(), req.getRootCommit(), req.getCausalScore(), req.getFixStrategy(), req.getFixAttempts(),
            req.getTestsPassed(), req.getTestsTotal(), req.getCoverageDelta(), req.getQualityScore(), req.getTokenBudgetUsed(),
            req.getRootCommit(), req.getCausalScore() * 100, req.getQualityScore() * 100, req.getFixAttempts(),
            req.getCausalChainNarrative(), req.getPatchSummary(),
            req.getTestsPassed(), req.getTestsTotal(), req.getCoverageDelta() * 100
        );
    }
}
