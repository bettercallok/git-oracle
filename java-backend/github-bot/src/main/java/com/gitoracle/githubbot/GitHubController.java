package com.gitoracle.githubbot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.kohsuke.github.GitHub;
import org.kohsuke.github.GHRepository;

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
            
            // Phase 3: Create the Pull Request
            GHRepository repo = github.getRepository(request.getRepoFullName());
            String defaultBranch = repo.getDefaultBranch();
            String sha = repo.getBranch(defaultBranch).getSHA1();
            
            String newBranchName = "gitoracle-fix-" + request.getJobId().substring(0, 8);
            repo.createRef("refs/heads/" + newBranchName, sha);
            logger.info("Created new branch: {}", newBranchName);
            
            // Note: The AI returns a patch diff string. For a true Git patch application in Java,
            // we would normally clone the repo locally, run `git apply`, and push. 
            // Since this API is stateless, we will simulate the commit by writing the patch 
            // to a `.patch` file on the repo for human review as part of the PR.
            // (In a production GitOracle, the orchestrator handles the local git tree).
            String patchFilePath = "gitoracle-fixes/fix-" + request.getJobId().substring(0, 8) + ".patch";
            repo.createContent()
                .path(patchFilePath)
                .content(request.getPatchDiff())
                .message("chore: apply GitOracle patch for " + request.getJobId())
                .branch(newBranchName)
                .commit();
            logger.info("Committed patch file to branch.");
            
            // Open the Pull Request
            repo.createPullRequest(
                "🤖 GitOracle Autonomous Fix: " + request.getCommitMessage(),
                newBranchName,
                defaultBranch,
                markdown
            );
            logger.info("Successfully opened Pull Request on {}", request.getRepoFullName());
            
            return "Successfully authenticated, created branch, and opened PR!";
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
