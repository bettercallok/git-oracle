package com.gitoracle.githubbot;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M5: the GitHub App installation token used to be embedded in the clone URL,
 * which put it on the process command line — and, because runCommand built its
 * failure message from the full argv, into both the log file and the HTTP
 * response body whenever a clone or push failed.
 *
 * The token no longer travels on argv at all (it goes through GIT_CONFIG_* in
 * the environment), so these test the remaining defence: git echoes remote URLs
 * in its own stderr, and that stderr is still surfaced to callers.
 */
class CredentialRedactionTest {

    @Test
    void stripsAnEmbeddedTokenFromARemoteUrl() {
        String stderr = "fatal: unable to access "
            + "'https://x-access-token:ghs_16C7e42F292c6912E7710c838347Ae178B4a@github.com/acme/app.git/': "
            + "Could not resolve host";

        String safe = GitHubController.redactCredentials(stderr);

        assertThat(safe).doesNotContain("ghs_16C7e42F292c6912E7710c838347Ae178B4a");
        assertThat(safe).contains("***@github.com/acme/app.git");
        // The useful part of the message survives — redaction must not make
        // failures undiagnosable.
        assertThat(safe).contains("Could not resolve host");
    }

    @Test
    void stripsBareGitHubTokensOutsideAUrl() {
        assertThat(GitHubController.redactCredentials("token ghp_abcdefghijklmnopqrstuvwxyz012345"))
            .doesNotContain("ghp_abcdefghijklmnopqrstuvwxyz012345");
        assertThat(GitHubController.redactCredentials("token ghs_abcdefghijklmnopqrstuvwxyz012345"))
            .doesNotContain("ghs_abcdefghijklmnopqrstuvwxyz012345");
        assertThat(GitHubController.redactCredentials(
                "token github_pat_11ABCDEFG0abcdefghijklmnopqrstuvwxyz0123456789"))
            .doesNotContain("github_pat_11ABCDEFG0abcdefghijklmnopqrstuvwxyz0123456789");
    }

    @Test
    void stripsAnAuthorizationHeaderValue() {
        // http.extraHeader is how the token now reaches git, so an echoed
        // header is the most likely remaining leak path.
        String safe = GitHubController.redactCredentials(
            "Authorization: Basic eC1hY2Nlc3MtdG9rZW46Z2hzX3NlY3JldA==");

        assertThat(safe).doesNotContain("eC1hY2Nlc3MtdG9rZW46Z2hzX3NlY3JldA==");
        assertThat(safe).contains("Authorization: Basic ***");
    }

    @Test
    void stripsGenericUserColonPasswordUrls() {
        String safe = GitHubController.redactCredentials(
            "remote: https://someuser:hunter2@example.com/x.git");

        assertThat(safe).doesNotContain("hunter2");
        assertThat(safe).contains("***@example.com");
    }

    @Test
    void leavesOrdinaryOutputAlone() {
        // A redactor that mangles normal errors makes people stop reading them.
        String ordinary = "error: patch failed: src/main/java/Foo.java:42\n"
            + "error: src/main/java/Foo.java: patch does not apply";

        assertThat(GitHubController.redactCredentials(ordinary)).isEqualTo(ordinary);
    }

    @Test
    void handlesTokenlessRemoteUrlsUnchanged() {
        String url = "fatal: repository 'https://github.com/acme/app.git/' not found";

        assertThat(GitHubController.redactCredentials(url)).isEqualTo(url);
    }

    @Test
    void isNullAndEmptySafe() {
        assertThat(GitHubController.redactCredentials(null)).isNull();
        assertThat(GitHubController.redactCredentials("")).isEmpty();
    }
}
