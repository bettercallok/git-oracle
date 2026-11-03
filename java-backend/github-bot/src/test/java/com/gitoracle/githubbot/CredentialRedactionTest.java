package com.gitoracle.githubbot;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The installation token must never reach a log line or the PR error that the
 * orchestrator stores as a job's (tenant-visible) state reason. A failed clone
 * used to report "Command failed: git clone ... https://x-access-token:<TOKEN>@github.com/...".
 */
class CredentialRedactionTest {

    private static final String TOKEN = "ghs_16C7e42F292c6912E7710c838347Ae178B4a";

    @Test
    void redactsTheTokenAndAnyUserinfoInAUrl() {
        String raw = "Command failed: git clone -c credential.helper= --branch main -- "
            + "https://x-access-token:" + TOKEN + "@github.com/acme/app.git . -> fatal: Remote branch main not found";

        String redacted = GitHubController.redactCredentials(raw, TOKEN);

        assertThat(redacted).doesNotContain(TOKEN)
            .contains("https://x-access-token:***@github.com/acme/app.git")
            .contains("Remote branch main not found");
    }

    @Test
    void redactsTheBasicAuthEncodingOfTheTokenToo() {
        String basic = Base64.getEncoder()
            .encodeToString(("x-access-token:" + TOKEN).getBytes(StandardCharsets.UTF_8));
        assertThat(GitHubController.redactCredentials("header AUTHORIZATION: basic " + basic, TOKEN))
            .doesNotContain(basic);
    }

    @Test
    void redactsUserinfoEvenWhenTheTokenValueIsUnknown() {
        assertThat(GitHubController.redactCredentials("https://user:s3cr3t@github.com/a/b", null))
            .isEqualTo("https://user:***@github.com/a/b");
    }

    @Test
    void nullStaysNull() {
        assertThat(GitHubController.redactCredentials(null, TOKEN)).isNull();
    }

    @Test
    void authEnvironmentCarriesTheTokenOnlyAsAnHttpHeader() {
        Map<String, String> env = GitHubController.gitAuthEnv(TOKEN);

        assertThat(env).containsEntry("GIT_CONFIG_COUNT", "1")
            .containsEntry("GIT_CONFIG_KEY_0", "http.https://github.com/.extraheader");
        assertThat(env.get("GIT_CONFIG_VALUE_0")).startsWith("AUTHORIZATION: basic ")
            .doesNotContain(TOKEN); // base64-encoded, never in a URL
    }

    /** The env-injected config is visible to git, and never persisted into the clone. */
    @Test
    void injectedConfigIsSeenByGitButNotWrittenToTheRepository(@org.junit.jupiter.api.io.TempDir Path tmp)
            throws Exception {
        Process init = new ProcessBuilder("git", "init", "-q", tmp.toString()).start();
        assertThat(init.waitFor()).isZero();

        ProcessBuilder pb = new ProcessBuilder("git", "config", "--get", "http.https://github.com/.extraheader")
            .directory(tmp.toFile());
        pb.environment().putAll(GitHubController.gitAuthEnv(TOKEN));
        Process get = pb.start();
        String seen = new String(get.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        assertThat(get.waitFor()).isZero();
        assertThat(seen).startsWith("AUTHORIZATION: basic ");

        assertThat(Files.readString(tmp.resolve(".git/config"))).doesNotContain("extraheader");
    }
}
