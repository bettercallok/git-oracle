package com.gitoracle.githubbot;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A regenerated fix for a job with an open PR goes onto that PR's branch as one
 * new commit. Exercised against a real local bare repository standing in for
 * GitHub: the git half is where a mistake would rewrite or lose someone's work.
 */
class RegeneratedFixPushTest {

    @TempDir
    Path tmp;

    private String origin;
    private String seed;

    @BeforeEach
    void setUp() throws Exception {
        origin = tmp.resolve("origin.git").toString();
        seed = tmp.resolve("seed").toString();
        git(tmp.toString(), "init", "--bare", "-b", "main", origin);
        git(tmp.toString(), "clone", origin, seed);
        configure(seed);
        write(seed, "UserService.java", "class UserService { void pay(User u) { u.getId(); } }\n");
        commitAll(seed, "initial");
        git(seed, "push", "origin", "main");

        // The PR as first opened: the original fix on its own branch.
        git(seed, "checkout", "-b", "gitoracle-fix-9e09ccf6");
        write(seed, "UserService.java", "class UserService { void pay(User u) { // BUG\n if (u == null) throw new IAE(); u.getId(); } }\n");
        commitAll(seed, "original fix");
        git(seed, "push", "origin", "gitoracle-fix-9e09ccf6");
        git(seed, "checkout", "main");
    }

    /** What github-bot does before pushing: clone the base branch and commit the regenerated fix on it. */
    private String workDirWithRegeneratedFix(String content) throws Exception {
        String work = tmp.resolve("work-" + System.nanoTime()).toString();
        git(tmp.toString(), "clone", "--branch", "main", origin, work);
        configure(work);
        write(work, "UserService.java", content);
        commitAll(work, "regenerated fix");
        return work;
    }

    @Test
    void addsOneFastForwardCommitWhoseContentIsExactlyTheNewFix() throws Exception {
        String prHeadBefore = git(origin, "rev-parse", "gitoracle-fix-9e09ccf6");
        String newFix = "class UserService { void pay(User u, Payment p) { if (u == null || p == null) throw new IAE(); } }\n";
        String work = workDirWithRegeneratedFix(newFix);

        String pushed = GitHubController.commitFixOntoBranch(work, "gitoracle-fix-9e09ccf6", "regenerated");

        assertThat(git(origin, "rev-parse", "gitoracle-fix-9e09ccf6")).isEqualTo(pushed);
        // History kept: the previous fix is the new commit's parent, not overwritten.
        assertThat(git(origin, "rev-parse", pushed + "^1")).isEqualTo(prHeadBefore);
        // The base hadn't moved, so no second parent.
        assertThat(git(origin, "rev-list", "--parents", "-n", "1", pushed).split(" ")).hasSize(2);
        assertThat(git(origin, "show", pushed + ":UserService.java") + "\n").isEqualTo(newFix);
    }

    @Test
    void whenTheBaseMovedOnTheBaseBecomesASecondParentSoThePrDiffIsOnlyTheFix() throws Exception {
        write(seed, "README.md", "unrelated change on main\n");
        commitAll(seed, "unrelated");
        git(seed, "push", "origin", "main");
        String work = workDirWithRegeneratedFix("class UserService { /* new fix */ }\n");

        String pushed = GitHubController.commitFixOntoBranch(work, "gitoracle-fix-9e09ccf6", "regenerated");

        String mainHead = git(origin, "rev-parse", "main");
        assertThat(git(origin, "rev-list", "--parents", "-n", "1", pushed)).endsWith(mainHead);
        // GitHub diffs a PR against the merge base; with main as a parent, that
        // diff is the fix alone.
        assertThat(git(origin, "merge-base", "main", pushed)).isEqualTo(mainHead);
        assertThat(git(origin, "diff", "--name-only", "main", pushed)).isEqualTo("UserService.java");
    }

    @Test
    void anIdenticalFixPushesNothing() throws Exception {
        String prHeadBefore = git(origin, "rev-parse", "gitoracle-fix-9e09ccf6");
        String sameAsPr = git(origin, "show", "gitoracle-fix-9e09ccf6:UserService.java") + "\n";
        String work = workDirWithRegeneratedFix(sameAsPr);

        assertThat(GitHubController.commitFixOntoBranch(work, "gitoracle-fix-9e09ccf6", "regenerated")).isNull();
        assertThat(git(origin, "rev-parse", "gitoracle-fix-9e09ccf6")).isEqualTo(prHeadBefore);
    }

    @Test
    void someoneElsesPushInTheMeantimeIsRejectedNotOverwritten() throws Exception {
        String work = workDirWithRegeneratedFix("class UserService { /* new fix */ }\n");
        // A human pushes to the PR branch after github-bot fetched it.
        git(seed, "fetch", "origin");
        git(work, "fetch", "origin", "refs/heads/gitoracle-fix-9e09ccf6");
        git(seed, "checkout", "gitoracle-fix-9e09ccf6");
        git(seed, "reset", "--hard", "origin/gitoracle-fix-9e09ccf6");
        write(seed, "NOTES.md", "reviewer's own commit\n");
        commitAll(seed, "reviewer edit");
        String humanCommit = git(seed, "rev-parse", "HEAD");

        // github-bot's view is now stale: simulate by pushing the human's commit
        // between its fetch and push, using a ref that predates it.
        String staleHead = git(origin, "rev-parse", "gitoracle-fix-9e09ccf6");
        git(seed, "push", "origin", "gitoracle-fix-9e09ccf6");
        String tree = git(work, "rev-parse", "HEAD^{tree}");
        String stale = git(work, "commit-tree", tree, "-p", staleHead, "-m", "stale");

        assertThatThrownBy(() -> git(work, "push", "origin", stale + ":refs/heads/gitoracle-fix-9e09ccf6"))
            .hasMessageContaining("rejected");
        assertThat(git(origin, "rev-parse", "gitoracle-fix-9e09ccf6")).isEqualTo(humanCommit);
    }

    @Test
    void onlyAPullRequestUrlOnTheSameRepositoryIsFollowed() {
        assertThat(GitHubController.pullRequestNumber("https://github.com/bettercallok/chillcall/pull/18", "bettercallok/chillcall"))
            .isEqualTo(18);
        assertThat(GitHubController.pullRequestNumber("https://github.com/bettercallok/chillcall/pull/18/", "BetterCallOK/chillcall"))
            .isEqualTo(18);
        assertThat(GitHubController.pullRequestNumber("https://github.com/someone/else/pull/18", "bettercallok/chillcall")).isNull();
        assertThat(GitHubController.pullRequestNumber("https://github.com/bettercallok/chillcall/issues/18", "bettercallok/chillcall")).isNull();
        assertThat(GitHubController.pullRequestNumber("https://evil.example/bettercallok/chillcall/pull/18", "bettercallok/chillcall")).isNull();
        assertThat(GitHubController.pullRequestNumber("", "bettercallok/chillcall")).isNull();
        assertThat(GitHubController.pullRequestNumber(null, "bettercallok/chillcall")).isNull();
    }

    @Test
    void theStatusEndpointOnlyAcceptsGitHubPullRequestUrls() {
        assertThat(GitHubController.repositoryOf("https://github.com/bettercallok/chillcall/pull/18"))
            .isEqualTo("bettercallok/chillcall");
        assertThat(GitHubController.repositoryOf("https://github.com/bettercallok/chillcall/issues/18")).isNull();
        assertThat(GitHubController.repositoryOf("https://evil.example/a/b/pull/1")).isNull();
        assertThat(GitHubController.repositoryOf(null)).isNull();
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private static void configure(String repo) throws Exception {
        git(repo, "config", "user.name", "test");
        git(repo, "config", "user.email", "test@example.com");
        git(repo, "config", "commit.gpgsign", "false");
    }

    private static void write(String repo, String file, String content) throws Exception {
        Files.writeString(Path.of(repo, file), content);
    }

    private static void commitAll(String repo, String message) throws Exception {
        git(repo, "add", ".");
        git(repo, "commit", "-m", message);
    }

    private static String git(String dir, String... args) throws Exception {
        String[] command = new String[args.length + 1];
        command[0] = "git";
        System.arraycopy(args, 0, command, 1, args.length);
        Process p = new ProcessBuilder(command).directory(new File(dir)).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        if (!p.waitFor(30, TimeUnit.SECONDS) || p.exitValue() != 0) {
            throw new RuntimeException("git " + String.join(" ", args) + " failed: " + out);
        }
        return out;
    }
}
