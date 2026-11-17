package com.gitoracle.testrunner;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/** A private repository clones with the orchestrator's token, which git never stores. */
class GitAuthEnvTest {

    @Test
    void theTokenReachesGitAsConfigForGithubOnly() throws Exception {
        var env = TestRunnerController.gitAuthEnv("ghs_secret");
        String expected = "AUTHORIZATION: basic "
            + Base64.getEncoder().encodeToString("x-access-token:ghs_secret".getBytes());

        Path dir = Files.createTempDirectory("git-auth-env");
        var out = TestRunnerController.run(dir, 10, env,
            "git", "config", "--get", "http.https://github.com/.extraheader");
        assertThat(out.output().trim()).isEqualTo(expected);
    }

    @Test
    void noTokenAddsNothing() {
        assertThat(TestRunnerController.gitAuthEnv(null)).isEmpty();
        assertThat(TestRunnerController.gitAuthEnv("  ")).isEmpty();
    }
}
