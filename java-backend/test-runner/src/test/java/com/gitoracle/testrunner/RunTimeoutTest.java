package com.gitoracle.testrunner;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * run() used to read stdout to EOF before calling waitFor(timeout), so the
 * timeout only started once the process had already exited, and it stopped
 * reading at the output cap, which left a noisy process blocked on a full pipe.
 */
class RunTimeoutTest {

    @TempDir
    Path dir;

    @Test
    void theTimeoutCoversTheWholeRun() throws Exception {
        long start = System.nanoTime();
        TestRunnerController.RunResult result = TestRunnerController.run(dir, 1, "sleep", "30");
        long seconds = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start);

        assertThat(result.timedOut()).isTrue();
        assertThat(result.success()).isFalse();
        assertThat(seconds).isLessThan(4);
    }

    @Test
    void aShellChildIsKilledWithItsParent() throws Exception {
        long start = System.nanoTime();
        // sh's own child would keep the output pipe open if only sh were killed.
        TestRunnerController.RunResult result = TestRunnerController.run(dir, 1, "sh", "-c", "echo started; sleep 30");
        long seconds = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start);

        assertThat(result.timedOut()).isTrue();
        assertThat(result.output()).contains("started");
        assertThat(seconds).isLessThan(4);
    }

    @Test
    void noisyOutputIsCappedButTheProcessStillFinishes() throws Exception {
        TestRunnerController.RunResult result =
            TestRunnerController.run(dir, 30, "sh", "-c", "yes | head -c 300000");

        assertThat(result.timedOut()).isFalse();
        assertThat(result.exitCode()).isZero();
        assertThat(result.output()).endsWith(TestRunnerController.TRUNCATION_MARKER);
        assertThat(result.output().length())
            .isEqualTo(TestRunnerController.OUTPUT_CAP + TestRunnerController.TRUNCATION_MARKER.length());
    }

    @Test
    void theExitCodeAndOutputOfANormalRunAreKept() throws Exception {
        TestRunnerController.RunResult result = TestRunnerController.run(dir, 10, "sh", "-c", "echo hi; exit 3");

        assertThat(result.timedOut()).isFalse();
        assertThat(result.exitCode()).isEqualTo(3);
        assertThat(result.output()).isEqualTo("hi\n");
    }

    @Test
    void theInContainerTimeoutStopsTheCommandAndKeepsItsQuoting() throws Exception {
        // Runs the same wrapper the sandbox gets, on this machine's own `timeout`
        // (or the unwrapped fallback where there is none).
        String wrapped = TestRunnerController.withinTimeout("echo 'it''s' && exit 0", 5);
        TestRunnerController.RunResult ok = TestRunnerController.run(dir, 10, "sh", "-c", wrapped);
        assertThat(ok.exitCode()).isZero();
        assertThat(ok.output()).isEqualTo("its\n");

        boolean hasTimeout = TestRunnerController.run(dir, 5, "sh", "-c", "timeout -k 1 5 true").success();
        if (hasTimeout) {
            TestRunnerController.RunResult slow = TestRunnerController.run(dir, 20, "sh", "-c",
                TestRunnerController.withinTimeout("sleep 30", 1));
            assertThat(slow.timedOut()).isFalse();
            assertThat(slow.exitCode()).isEqualTo(124);
        }
    }

    @Test
    void theWrapperStopsAHungCommandAsPid1InTheBusyboxImages() throws Exception {
        // The sandbox runs the wrapper as the container's PID 1 with no init.
        // Busybox `timeout` (node/gradle/golang images) could not stop the
        // command from there while the wrapper used `exec`.
        org.junit.jupiter.api.Assumptions.assumeTrue(
            TestRunnerController.run(dir, 10, "docker", "info").success(), "docker not available");
        String image = "node@sha256:fb4cd12c85ee03686f6af5362a0b0d56d50c58a04632e6c0fb8363f609372293";

        long start = System.nanoTime();
        TestRunnerController.RunResult hung = TestRunnerController.run(dir, 120, "docker", "run", "--rm",
            "--user", "1000", "--entrypoint", "sh", image, "-c",
            TestRunnerController.withinTimeout("trap '' TERM; sleep 60", 2));
        long seconds = (System.nanoTime() - start) / 1_000_000_000L;

        assertThat(hung.timedOut()).isFalse();
        assertThat(hung.exitCode()).isEqualTo(124);
        assertThat(seconds).isLessThan(40);

        TestRunnerController.RunResult failing = TestRunnerController.run(dir, 120, "docker", "run", "--rm",
            "--user", "1000", "--entrypoint", "sh", image, "-c",
            TestRunnerController.withinTimeout("exit 3", 30));
        assertThat(failing.exitCode()).isEqualTo(3);
    }

    @Test
    void containerNamesAreUniqueAndOnlyEverCarryAValidJobId() {
        String a = TestRunnerController.containerName("job-123");
        String b = TestRunnerController.containerName("job-123");

        assertThat(a).matches("gitoracle-test-job-123-[0-9a-f]{8}");
        assertThat(a).isNotEqualTo(b);
        assertThat(TestRunnerController.containerName("../$(rm -rf /)")).matches("gitoracle-test-job-[0-9a-f]{8}");
    }
}
