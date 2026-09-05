package com.gitoracle.githubbot;

import com.gitoracle.githubbot.security.RepoRefValidator;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Base64;
import java.util.Map;
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

        // Declared outside the try so the finally block can clean it up on
        // every exit path, not just the successful one.
        String workDir = null;

        try {
            // repoFullName and sourceBranch flow from upstream pipeline events into
            // a `git clone`/`git checkout` invocation below. Before this validation,
            // a crafted sourceBranch starting with `-` was passed straight to
            // `--branch` with no terminator, and repoFullName had no format check
            // at all. Reject anything that isn't a plain "owner/repo" slug or a
            // well-formed branch name before either reaches `git`.
            try {
                RepoRefValidator.validateRepoUrl("https://github.com/" + request.getRepoFullName());
            } catch (RepoRefValidator.InvalidRepoRefException e) {
                logger.warn("Rejected PR request for job {}: invalid repoFullName: {}",
                    request.getJobId(), e.getMessage());
                return ResponseEntity.badRequest().body(new PullRequestResult(false, null, e.getMessage()));
            }
            String requestedBranch;
            try {
                requestedBranch = RepoRefValidator.validateBranch(request.getSourceBranch());
            } catch (RepoRefValidator.InvalidRepoRefException e) {
                logger.warn("Rejected PR request for job {}: invalid sourceBranch: {}",
                    request.getJobId(), e.getMessage());
                return ResponseEntity.badRequest().body(new PullRequestResult(false, null, e.getMessage()));
            }

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
            String baseBranch = (requestedBranch != null && !requestedBranch.isBlank())
                ? requestedBranch : defaultBranch;

            String newBranchName = "gitoracle-fix-" + request.getJobId().substring(0, 8);

            // Stateful Git Operations
            workDir = "/tmp/gitoracle-bot/" + UUID.randomUUID().toString();
            new File(workDir).mkdirs();

            // Tokenless URL. This used to be
            //   https://x-access-token:<TOKEN>@github.com/owner/repo.git
            // which put the installation token in three places at once: the
            // process argv (world-readable via /proc/<pid>/cmdline on Linux, so
            // any local user could read it with `ps`), the clone's own
            // .git/config as remote.origin.url, and — worst — inside
            // runCommand's failure message, which is logged AND returned in the
            // HTTP response body.
            //
            // Credentials now travel in the environment instead (see
            // gitAuthEnv), which /proc exposes only to the owning user.
            String cloneUrl = "https://github.com/" + request.getRepoFullName() + ".git";
            Map<String, String> authEnv = gitAuthEnv(token);

            logger.info("Cloning repository (branch: {}) into {}", baseBranch, workDir);
            // One retry on a transient network failure — confirmed live: a git-bot
            // clone failed with "Could not resolve host: github.com" on a machine
            // that resolved DNS fine a moment before and after (the same blip hit
            // the fixer's clone and test-runner's clone earlier in the pipeline).
            try {
                runCommand(workDir, authEnv, "git", "clone", "-c", "credential.helper=", "--branch", baseBranch, "--", cloneUrl, ".");
            } catch (Exception e) {
                logger.warn("Clone failed for job {}, retrying once: {}", request.getJobId(), e.getMessage());
                Thread.sleep(2000);
                runCommand(workDir, authEnv, "git", "clone", "-c", "credential.helper=", "--branch", baseBranch, "--", cloneUrl, ".");
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
            runCommand(workDir, authEnv, "git", "-c", "credential.helper=", "push", "origin", newBranchName);
            
            // Open the Pull Request
            GHPullRequest pr = repo.createPullRequest(
                "🤖 GitOracle Autonomous Fix: " + request.getCommitMessage(),
                newBranchName,
                baseBranch,
                markdown
            );
            logger.info("Successfully opened Pull Request on {}: {}", request.getRepoFullName(), pr.getHtmlUrl());

            return ResponseEntity.ok(new PullRequestResult(true, pr.getHtmlUrl().toString(), null));
        } catch (Exception e) {
            logger.error("Failed to process PR request", e);
            // Distinguishable from success at the HTTP layer (was previously a 200
            // with an "Error: " string body — the orchestrator never checked the
            // body, so it recorded the job as PR_OPENED regardless of outcome).
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new PullRequestResult(false, null, e.getMessage()));
        } finally {
            // Was only reached on the success path, so any failure — a rejected
            // patch, a push conflict, a GitHub outage — left a full checkout of
            // the customer's repository under /tmp indefinitely, accumulating
            // one directory per failed job.
            deleteWorkDir(workDir);
        }
    }

    private void deleteWorkDir(String workDir) {
        if (workDir == null) return;
        try {
            runCommand("/tmp", Map.of(), "rm", "-rf", workDir);
        } catch (Exception cleanupFailure) {
            logger.warn("Could not remove working directory {}: {}", workDir, cleanupFailure.getMessage());
        }
    }

    /**
     * Git configuration carrying the installation token, passed through the
     * environment rather than the command line.
     *
     * <p>{@code GIT_CONFIG_COUNT}/{@code GIT_CONFIG_KEY_n}/
     * {@code GIT_CONFIG_VALUE_n} is git's supported way to set config without
     * writing a file or passing {@code -c} on argv. That distinction is the
     * whole point: on Linux {@code /proc/<pid>/cmdline} is world-readable, so
     * anything on argv is visible to every local user via {@code ps}, while
     * {@code /proc/<pid>/environ} is readable only by the process owner.
     *
     * <p>{@code http.extraHeader} also keeps the credential out of the clone's
     * {@code .git/config}, which an embedded-token remote URL does not — that
     * file persisted on disk with a live token in it for the lifetime of the
     * checkout.
     */
    private Map<String, String> gitAuthEnv(String token) {
        String basic = Base64.getEncoder().encodeToString(
            ("x-access-token:" + token).getBytes(StandardCharsets.UTF_8));
        return Map.of(
            "GIT_CONFIG_COUNT", "1",
            "GIT_CONFIG_KEY_0", "http.extraHeader",
            "GIT_CONFIG_VALUE_0", "Authorization: Basic " + basic
        );
    }

    public record PullRequestResult(boolean success, String prUrl, String error) {}
    
    /** Local git operations that need no credentials. */
    private void runCommand(String dir, String... command) throws Exception {
        runCommand(dir, Map.of(), command);
    }

    private void runCommand(String dir, Map<String, String> extraEnv, String... command) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(new File(dir));
        pb.environment().putAll(extraEnv);
        Process p = pb.start();
        if (!p.waitFor(60, TimeUnit.SECONDS) || p.exitValue() != 0) {
            String error = new String(p.getErrorStream().readAllBytes());
            // Only the program name, never the full argv, and the stderr is
            // scrubbed. This message is logged AND returned in the HTTP
            // response body, and the clone/push arguments used to include a URL
            // with the installation token embedded in it — so a failed clone
            // published a live credential to both the log file and the caller.
            // git also echoes the remote URL back in several of its own error
            // messages, which is why the stderr is redacted rather than trusted.
            throw new RuntimeException(
                "Command failed: " + command[0] + " -> " + redactCredentials(error));
        }
    }

    /**
     * Strips anything credential-shaped out of text that is about to be logged
     * or returned to a caller.
     *
     * <p>Defence in depth: the token is no longer placed on the command line,
     * but git echoes remote URLs in its own error output, and a future change
     * could reintroduce an embedded-credential URL somewhere this code does not
     * control.
     */
    static String redactCredentials(String text) {
        if (text == null || text.isEmpty()) return text;
        return text
            // https://user:secret@host -> https://***@host
            .replaceAll("(?i)(https?://)[^/\\s:@]+:[^/\\s@]+@", "$1***@")
            // Bare GitHub tokens, whether or not they sit in a URL.
            .replaceAll("gh[pousr]_[A-Za-z0-9]{20,}", "***")
            .replaceAll("github_pat_[A-Za-z0-9_]{20,}", "***")
            // Any Authorization header value that made it into output.
            .replaceAll("(?i)(Authorization:\\s*(?:Basic|Bearer)\\s+)\\S+", "$1***");
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
