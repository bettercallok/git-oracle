package com.gitoracle.githubbot;

import com.gitoracle.githubbot.security.RepoRefValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.kohsuke.github.GHPullRequest;
import org.kohsuke.github.GitHub;
import org.kohsuke.github.GHRepository;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

            // A regenerated fix for a job that already has an open PR goes onto that
            // PR's branch. It used to never reach here: the orchestrator dropped the
            // passing result as a duplicate delivery, so "Regenerate Fix" on a job
            // with a PR produced nothing (job 9e09ccf6, chillcall#18).
            GHPullRequest existingPr = findUpdatablePullRequest(repo, request);
            boolean replacesClosedPr = existingPr == null
                && request.getExistingPrUrl() != null && !request.getExistingPrUrl().isBlank();

            String newBranchName = "gitoracle-fix-" + request.getJobId().substring(0, 8);
            if (replacesClosedPr) {
                // The old PR's branch may still exist; don't collide with it.
                newBranchName += "-r" + (System.currentTimeMillis() / 1000);
            }

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
                runCommand(workDir, "git", "clone", "-c", "credential.helper=", "--branch", baseBranch, "--", cloneUrl, ".");
            } catch (Exception e) {
                logger.warn("Clone failed for job {}, retrying once: {}", request.getJobId(), e.getMessage());
                Thread.sleep(2000);
                runCommand(workDir, "git", "clone", "-c", "credential.helper=", "--branch", baseBranch, "--", cloneUrl, ".");
            }

            logger.info("Creating branch {}", newBranchName);
            runCommand(workDir, "git", "checkout", "-b", newBranchName);
            
            // Write patch file and apply
            File patchFile = new File(workDir, "fix.patch");
            Files.writeString(patchFile.toPath(), request.getPatchDiff());
            
            logger.info("Applying patch...");
            runCommand(workDir, "git", "apply", "fix.patch");
            patchFile.delete(); // cleanup
            
            logger.info("Committing...");
            runCommand(workDir, "git", "config", "user.name", "GitOracle Bot");
            runCommand(workDir, "git", "config", "user.email", "bot@gitoracle.ai");
            runCommand(workDir, "git", "add", ".");
            runCommand(workDir, "git", "commit", "-m", "🤖 GitOracle Autonomous Fix: " + request.getCommitMessage());

            if (existingPr != null) {
                String prUrl = updatePullRequest(workDir, existingPr, request, markdown);
                runCommand("/tmp", "rm", "-rf", workDir);
                return ResponseEntity.ok(new PullRequestResult(true, prUrl, null));
            }

            logger.info("Pushing {}...", newBranchName);
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

    /** {@code state} is "open" or "closed"; {@code by} merged or closed it (null while open). */
    public record PullRequestStatus(String prUrl, String state, boolean merged, String by) {}

    /**
     * Whether a PR GitOracle opened is still open, merged or closed — asked of
     * GitHub directly. The orchestrator's PrStatusReconciler polls this for
     * jobs in PR_OPENED, because the pull_request webhook that would otherwise
     * report it can't reach a local deployment (it never had).
     */
    @GetMapping("/pull-request/status")
    public ResponseEntity<?> pullRequestStatus(@RequestParam("url") String prUrl) {
        String repoFullName = repositoryOf(prUrl);
        Integer number = repoFullName == null ? null : pullRequestNumber(prUrl, repoFullName);
        if (number == null) {
            return ResponseEntity.badRequest().body(java.util.Map.of("error", "not a GitHub pull request URL: " + prUrl));
        }
        try {
            RepoRefValidator.validateRepoUrl("https://github.com/" + repoFullName);
            GHPullRequest pr = githubClient.getAuthenticatedGitHub().getRepository(repoFullName).getPullRequest(number);
            boolean open = pr.getState() == org.kohsuke.github.GHIssueState.OPEN;
            boolean merged = !open && pr.isMerged();
            String by = null;
            if (merged && pr.getMergedBy() != null) {
                by = pr.getMergedBy().getLogin();
            } else if (!open && pr.getClosedBy() != null) {
                by = pr.getClosedBy().getLogin();
            }
            return ResponseEntity.ok(new PullRequestStatus(prUrl, open ? "open" : "closed", merged, by));
        } catch (RepoRefValidator.InvalidRepoRefException e) {
            return ResponseEntity.badRequest().body(java.util.Map.of("error", e.getMessage()));
        } catch (Exception e) {
            logger.warn("Could not read status of {}: {}", prUrl, e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(java.util.Map.of("error", String.valueOf(e.getMessage())));
        }
    }

    /** "owner/repo" from a GitHub pull request URL, or null. */
    static String repositoryOf(String prUrl) {
        if (prUrl == null) return null;
        Matcher m = PR_URL.matcher(prUrl.trim());
        return m.matches() ? m.group(1) : null;
    }

    private static final Pattern PR_URL = Pattern.compile("^https://github\\.com/([^/]+/[^/]+)/pull/(\\d+)/?$");

    /**
     * The PR number in {@code prUrl}, or null unless it is a pull request URL
     * on exactly {@code repoFullName}. A URL from another repository is never
     * followed, whatever the job record says.
     */
    static Integer pullRequestNumber(String prUrl, String repoFullName) {
        if (prUrl == null || repoFullName == null) return null;
        Matcher m = PR_URL.matcher(prUrl.trim());
        if (!m.matches() || !m.group(1).equalsIgnoreCase(repoFullName)) return null;
        return Integer.valueOf(m.group(2));
    }

    /**
     * The job's existing PR, if it is still open and its branch lives in this
     * repository (a fork's branch can't be pushed to). Null means: open a new PR.
     */
    private GHPullRequest findUpdatablePullRequest(GHRepository repo, PullRequestRequest request) throws IOException {
        Integer number = pullRequestNumber(request.getExistingPrUrl(), request.getRepoFullName());
        if (number == null) {
            if (request.getExistingPrUrl() != null && !request.getExistingPrUrl().isBlank()) {
                logger.warn("Job {}: existing PR {} is not a PR on {} — opening a new one",
                    request.getJobId(), request.getExistingPrUrl(), request.getRepoFullName());
            }
            return null;
        }
        GHPullRequest pr = repo.getPullRequest(number);
        if (pr.getState() != org.kohsuke.github.GHIssueState.OPEN) {
            logger.info("Job {}: PR #{} is {} — opening a new one for the regenerated fix",
                request.getJobId(), number, pr.getState());
            return null;
        }
        if (pr.getHead().getRepository() == null
                || !request.getRepoFullName().equalsIgnoreCase(pr.getHead().getRepository().getFullName())) {
            logger.warn("Job {}: PR #{} comes from another repository — opening a new one", request.getJobId(), number);
            return null;
        }
        return pr;
    }

    /**
     * Puts the fix committed in {@code workDir} (on top of the base branch) onto
     * {@code pr}'s branch as one new commit, without rewriting its history.
     *
     * <p>The patch was generated against the base branch, not the PR branch, so
     * applying it there would conflict with the previous fix. Instead the new
     * commit takes its tree straight from the base+patch commit, with the PR's
     * head as parent — so the push is a fast-forward and the PR's total diff
     * becomes exactly the new fix. When the base has moved on since the PR was
     * opened, the base commit is a second parent, which keeps GitHub's diff
     * (computed from the merge base) to the fix alone.
     */
    private String updatePullRequest(String workDir, GHPullRequest pr, PullRequestRequest request, String markdown)
            throws Exception {
        String headRef = RepoRefValidator.validateBranch(pr.getHead().getRef());
        logger.info("Updating PR #{} (branch {}) for job {}", pr.getNumber(), headRef, request.getJobId());

        String url = pr.getHtmlUrl().toString();
        String newCommit = commitFixOntoBranch(workDir, headRef,
            "🤖 GitOracle: regenerated fix — " + request.getCommitMessage());
        if (newCommit == null) {
            logger.info("Regenerated fix for job {} is identical to PR #{} — nothing to push", request.getJobId(), pr.getNumber());
            pr.comment("🤖 GitOracle regenerated this fix and arrived at exactly the same change — no new commit.");
            return url;
        }

        pr.setBody(markdown);
        pr.comment("🤖 GitOracle regenerated this fix as requested, and the full test suite passed with it. "
            + "The PR now contains only the new fix (commit " + newCommit.substring(0, 7) + ").");
        logger.info("Updated PR {} for job {} with commit {}", url, request.getJobId(), newCommit);
        return url;
    }

    /**
     * The git half of {@link #updatePullRequest}: {@code workDir} is a clone of
     * the base branch whose HEAD commit is base + fix. Pushes one commit with
     * that tree onto {@code origin/<headRef>} and returns its sha, or null when
     * the branch already has exactly that content.
     */
    static String commitFixOntoBranch(String workDir, String headRef, String message) throws Exception {
        runCommand(workDir, "git", "-c", "credential.helper=", "fetch", "origin", "refs/heads/" + headRef);
        String prHead = runCommandOutput(workDir, "git", "rev-parse", "FETCH_HEAD");
        String baseHead = runCommandOutput(workDir, "git", "rev-parse", "HEAD~1");
        String tree = runCommandOutput(workDir, "git", "rev-parse", "HEAD^{tree}");

        if (tree.equals(runCommandOutput(workDir, "git", "rev-parse", prHead + "^{tree}"))) {
            return null;
        }

        List<String> commitTree = new ArrayList<>(List.of("git", "commit-tree", tree, "-p", prHead));
        if (exitCode(workDir, "git", "merge-base", "--is-ancestor", baseHead, prHead) != 0) {
            commitTree.addAll(List.of("-p", baseHead));
        }
        commitTree.addAll(List.of("-m", message));
        String newCommit = runCommandOutput(workDir, commitTree.toArray(String[]::new));

        // A fast-forward push: if someone else pushed to the branch meanwhile, it
        // is rejected rather than overwriting their work.
        runCommand(workDir, "git", "-c", "credential.helper=", "push", "origin", newCommit + ":refs/heads/" + headRef);
        return newCommit;
    }

    private static String runCommandOutput(String dir, String... command) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(new File(dir));
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        if (!p.waitFor(60, TimeUnit.SECONDS) || p.exitValue() != 0) {
            String error = new String(p.getErrorStream().readAllBytes());
            throw new RuntimeException("Command failed: " + String.join(" ", command) + " -> " + error);
        }
        return out;
    }

    private static int exitCode(String dir, String... command) throws Exception {
        Process p = new ProcessBuilder(command).directory(new File(dir)).redirectErrorStream(true).start();
        p.getInputStream().readAllBytes();
        if (!p.waitFor(60, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            throw new RuntimeException("Command timed out: " + String.join(" ", command));
        }
        return p.exitValue();
    }
    
    private static void runCommand(String dir, String... command) throws Exception {
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
