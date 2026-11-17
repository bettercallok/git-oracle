package com.gitoracle.githubbot;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** A hung git command times out and is killed, instead of hanging the request. */
class CommandTimeoutTest {

    private static final String TMP = System.getProperty("java.io.tmpdir");

    @Test
    void aCommandPastItsTimeoutIsKilled() {
        long start = System.nanoTime();
        assertThatThrownBy(() -> GitHubController.exec(TMP, Map.of(), 1, "sh", "-c", "sleep 30"))
            .hasMessageContaining("timed out after 1s");
        assertThat((System.nanoTime() - start) / 1_000_000_000L).isLessThan(10);
    }

    @Test
    void aChattyCommandDoesNotBlockOnAFullPipe() throws Exception {
        // ~1MB on each stream: more than a pipe buffer holds.
        var r = GitHubController.exec(TMP, Map.of(), 20, "sh", "-c",
            "i=0; while [ $i -lt 20000 ]; do echo xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx; echo yyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyy >&2; i=$((i+1)); done");
        assertThat(r.exitCode()).isZero();
        assertThat(r.stdout().length()).isGreaterThan(900_000);
        assertThat(r.stderr().length()).isGreaterThan(800_000);
    }

    @Test
    void outputAndExitCodeComeBack() throws Exception {
        var r = GitHubController.exec(TMP, Map.of("X", "1"), 10, "sh", "-c", "echo out; echo err >&2; exit 3");
        assertThat(r).isEqualTo(new GitHubController.CommandResult(3, "out\n", "err\n"));
    }
}
