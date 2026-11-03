package ai.gitoracle.core.security;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/** Every way of naming one GitHub repository maps to one allow-list entry; anything else to none. */
class RepoNamesTest {

    @ParameterizedTest
    @ValueSource(strings = {
        "owner/repo", "Owner/Repo", " owner/repo ",
        "https://github.com/owner/repo", "https://github.com/Owner/Repo.git",
        "https://github.com/owner/repo/", "http://www.github.com/owner/repo", "owner/repo.git"})
    void allFormsOfOneRepoGiveOneSlug(String given) {
        assertThat(RepoNames.slug(given)).isEqualTo("owner/repo");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
        "repo", "owner/repo/extra", "https://gitlab.com/owner/repo", "https://github.com/owner",
        "../etc/passwd", "owner/..", "file:///tmp/repo", "https://github.com/owner/repo?x=1"})
    void anythingElseIsNotARepository(String given) {
        assertThat(RepoNames.slug(given)).isNull();
    }
}
