package com.gitoracle.testrunner;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * H9: moving the sandbox daemon out of the host (off the mounted docker.sock,
 * onto a DinD sidecar) makes a previously-impossible failure reachable — the
 * bind mount is resolved by the DAEMON, so a workspace path that exists in this
 * service can be absent in the daemon, and Docker does not error on that. It
 * creates the missing bind source as an empty directory.
 *
 * That mattered because "nothing" looked like success: pytest exits 5 on an
 * empty tree, and runInDocker deliberately treats "no tests collected" as a
 * safe pass. A workspace path that did not line up would therefore have marked
 * EVERY patch as passing its tests, silently.
 */
class EmptyWorkspaceGuardTest {

    @Test
    void theGuardRunsBeforeTheTestCommand() {
        String guarded = TestRunnerController.guardEmptyWorkspace("pytest -q");

        // The emptiness check must precede the command, or the command runs
        // against an empty tree first and the guard is pointless.
        assertThat(guarded.indexOf("ls -A /repo")).isLessThan(guarded.indexOf("pytest -q"));
    }

    @Test
    void theGuardStillRunsTheOriginalCommand() {
        assertThat(TestRunnerController.guardEmptyWorkspace("mvn -B test")).contains("mvn -B test");
        assertThat(TestRunnerController.guardEmptyWorkspace("npm test")).contains("npm test");
    }

    @Test
    void theGuardChecksTheRepoMountSpecifically() {
        String guarded = TestRunnerController.guardEmptyWorkspace("pytest");

        assertThat(guarded).contains("/repo");
        assertThat(guarded).contains("ls -A");
    }

    @Test
    void theGuardExitsWithASentinelDistinctFromEveryFrameworkExitCode() {
        String guarded = TestRunnerController.guardEmptyWorkspace("pytest");

        // 91: clear of pytest (0-5) and of Maven/Gradle/npm (0/1), so it can
        // never be mistaken for a test outcome — in particular it must not
        // collide with pytest's 5, which IS treated as a safe pass.
        assertThat(guarded).contains("exit 91");
        assertThat(guarded).doesNotContain("exit 5;");
    }

    @Test
    void theGuardExplainsItselfOnStderr() {
        String guarded = TestRunnerController.guardEmptyWorkspace("pytest");

        assertThat(guarded).contains(">&2");
        assertThat(guarded).contains("bind mount did not resolve");
    }

    @Test
    void aCommandWithShellMetacharactersIsNotMangled() {
        // Test commands legitimately contain && and pipes; the guard prefixes
        // rather than wraps, so they must survive intact.
        String command = "cd backend && pytest -q | tee out.txt";

        assertThat(TestRunnerController.guardEmptyWorkspace(command)).endsWith(command);
    }
}
