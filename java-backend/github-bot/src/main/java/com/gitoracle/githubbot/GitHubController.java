package com.gitoracle.githubbot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.kohsuke.github.GHPullRequest;
import org.kohsuke.github.GitHub;
import org.kohsuke.github.GHRepository;

import java.io.File;
import java.nio.file.Files;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@RestController
public class GitHubController {
    private static final Logger logger = LoggerFactory.getLogger(GitHubController.class);
    
    private final GitHubClient githubClient;
    
    public GitHubController(GitHubClient githubClient) {
        this.githubClient = githubClient;
    }

    @PostMapping("/pull-request")
    public ResponseEntity<PullRequestResult> createPullRequest(@RequestBody PullRequestRequest request) {
        logger.info("Received request to open PR for job: {}", request.getJobId());

        try {
            String markdown = generateMarkdown(request);

            // Connect to GitHub
            GitHub github = githubClient.getAuthenticatedGitHub();
            String token = githubClient.getLatestInstallationToken();
            GHRepository repo = github.getRepository(request.getRepoFullName());
            String defaultBranch = repo.getDefaultBranch();
            // The branch the fix was actually read from and tested against — falls
            // back to the repo's default when the job didn't request one. Used both
            // as the clone target below and as the PR's base, so the new fix branch
            // is cut from, and merges back into, the same code the patch was built
            // against. Previously this was always defaultBranch regardless of what
            // the rest of the pipeline used.
            String baseBranch = (request.getSourceBranch() != null && !request.getSourceBranch().isBlank())
                ? request.getSourceBranch() : defaultBranch;

            String newBranchName = "gitoracle-fix-" + request.getJobId().substring(0, 8);

            // Stateful Git Operations
            String workDir = "/tmp/gitoracle-bot/" + UUID.randomUUID().toString();
            new File(workDir).mkdirs();

            String cloneUrl = "https://x-access-token:" + token + "@github.com/" + request.getRepoFullName() + ".git";

            logger.info("Cloning repository (branch: {}) into {}", baseBranch, workDir);
            // One retry on a transient network failure — confirmed live: a git-bot
            // clone failed with "Could not resolve host: github.com" on a machine
            // that resolved DNS fine a moment before and after (the same blip hit
            // the fixer's clone and test-runner's clone earlier in the pipeline).
            try {
                runCommand(workDir, "git", "clone", "-c", "credential.helper=", "--branch", baseBranch, cloneUrl, ".");
            } catch (Exception e) {
                logger.warn("Clone failed for job {}, retrying once: {}", request.getJobId(), e.getMessage());
                Thread.sleep(2000);
                runCommand(workDir, "git", "clone", "-c", "credential.helper=", "--branch", baseBranch, cloneUrl, ".");
            }

            logger.info("Creating branch {}", newBranchName);
            runCommand(workDir, "git", "checkout", "-b", newBranchName);
            
            // Write patch file and apply
            File patchFile = new File(workDir, "fix.patch");
            Files.writeString(patchFile.toPath(), request.getPatchDiff());
            
            logger.info("Applying patch...");
            runCommand(workDir, "git", "apply", "fix.patch");
            patchFile.delete(); // cleanup
            
            logger.info("Committing and pushing...");
            runCommand(workDir, "git", "config", "user.name", "GitOracle Bot");
            runCommand(workDir, "git", "config", "user.email", "bot@gitoracle.ai");
            runCommand(workDir, "git", "add", ".");
            runCommand(workDir, "git", "commit", "-m", "🤖 GitOracle Autonomous Fix: " + request.getCommitMessage());
            runCommand(workDir, "git", "-c", "credential.helper=", "push", "origin", newBranchName);
            
            // Open the Pull Request
            GHPullRequest pr = repo.createPullRequest(
                "🤖 GitOracle Autonomous Fix: " + request.getCommitMessage(),
                newBranchName,
                baseBranch,
                markdown
            );
            logger.info("Successfully opened Pull Request on {}: {}", request.getRepoFullName(), pr.getHtmlUrl());

            // Cleanup
            runCommand("/tmp", "rm", "-rf", workDir);

            return ResponseEntity.ok(new PullRequestResult(true, pr.getHtmlUrl().toString(), null));
        } catch (Exception e) {
            logger.error("Failed to process PR request", e);
            // Distinguishable from success at the HTTP layer (was previously a 200
            // with an "Error: " string body — the orchestrator never checked the
            // body, so it recorded the job as PR_OPENED regardless of outcome).
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new PullRequestResult(false, null, e.getMessage()));
        }
    }

    public record PullRequestResult(boolean success, String prUrl, String error) {}
    
    private void runCommand(String dir, String... command) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(new File(dir));
        Process p = pb.start();
        if (!p.waitFor(60, TimeUnit.SECONDS) || p.exitValue() != 0) {
            String error = new String(p.getErrorStream().readAllBytes());
            throw new RuntimeException("Command failed: " + String.join(" ", command) + " -> " + error);
        }
    }
    
    private String generateMarkdown(PullRequestRequest req) {
        return String.format(
            "<!-- gitoracle:job_id:%s -->\n" +
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
            "---\n*This PR was generated autonomously by GitOracle. Not satisfied? Use the [GitOracle Dashboard](http://localhost:5173) to regenerate with your instructions.*",
            req.getJobId(),
            req.getJobId(), req.getRootCommit(), req.getCausalScore(), req.getFixStrategy(), req.getFixAttempts(),
            req.getTestsPassed(), req.getTestsTotal(), req.getCoverageDelta(), req.getQualityScore(), req.getTokenBudgetUsed(),
            req.getRootCommit(), req.getCausalScore() * 100, req.getQualityScore() * 100, req.getFixAttempts(),
            req.getCausalChainNarrative(), req.getPatchSummary(),
            req.getTestsPassed(), req.getTestsTotal(), req.getCoverageDelta() * 100
        );
    }
}
