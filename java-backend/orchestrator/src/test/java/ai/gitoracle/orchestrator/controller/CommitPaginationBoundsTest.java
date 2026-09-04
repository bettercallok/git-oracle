package ai.gitoracle.orchestrator.controller;

import org.junit.jupiter.api.Test;

import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * H7: `page` was clamped only at the bottom (`Math.max(1, page)`), and the
 * resulting skip ran against a LAZY PagedIterable — so skipped commits are not
 * free, they are fetched from the GitHub API a page at a time and discarded.
 * `?page=20000000` walks two billion commits: it pins a request thread and
 * burns the installation's GitHub quota, which is shared across tenants.
 *
 * These pin the arithmetic and the repo/sha shape rules directly. The
 * controller method itself needs an authenticated GitHub client to reach, and
 * the whole point of the fix is that these inputs are rejected BEFORE any
 * GitHub call happens — so the bounds are what there is to test, and testing
 * them here keeps the suite free of network dependencies.
 */
class CommitPaginationBoundsTest {

    private static final int MAX_PAGE = 100;
    private static final long MAX_SKIP = 5_000;
    private static final int MAX_PER_PAGE = 100;

    private static final Pattern OWNER_REPO =
        Pattern.compile("^[A-Za-z0-9._-]{1,100}/[A-Za-z0-9._-]{1,100}$");

    /** Mirrors the controller: long arithmetic, then the two bounds. */
    private static boolean accepted(int page, int perPage) {
        if (page < 1 || page > MAX_PAGE) return false;
        int safePerPage = Math.min(Math.max(1, perPage), MAX_PER_PAGE);
        long skip = (long) (page - 1) * safePerPage;
        return skip <= MAX_SKIP;
    }

    @Test
    void theDocumentedAttackPageIsRejected() {
        // The plan's verification case.
        assertThat(accepted(100_000_000, 30)).isFalse();
    }

    @Test
    void aPageDeepEnoughToPinAThreadIsRejected() {
        assertThat(accepted(20_000_000, 100)).isFalse();
        assertThat(accepted(1_000_000, 30)).isFalse();
        assertThat(accepted(MAX_PAGE + 1, 30)).isFalse();
    }

    @Test
    void theOldIntArithmeticSilentlyWrappedToAWrongSkip() {
        // (page-1)*perPage in int overflows and wraps. For the documented attack
        // page it stays POSITIVE, which is the nastier of the two outcomes: no
        // exception, just a silently wrong skip that still walks 1.4 billion
        // commits' worth of GitHub API pages.
        int page = 100_000_000;
        int perPage = 100;

        assertThat((page - 1) * perPage).isEqualTo(1_410_065_308);          // wrapped
        assertThat((long) (page - 1) * perPage).isEqualTo(9_999_999_900L);  // the truth
        assertThat((page - 1) * perPage).isNotEqualTo((long) (page - 1) * perPage);

        assertThat(accepted(page, perPage)).isFalse();
    }

    @Test
    void someOffsetsWrappedNegativeAndWouldThrowFromStreamSkip() {
        // The other overflow outcome: a negative skip, which Stream.skip
        // rejects with IllegalArgumentException — a 500 for what is plainly a
        // client error.
        int page = 21_474_838;
        int perPage = 100;

        assertThat((page - 1) * perPage).isNegative();
        assertThat((long) (page - 1) * perPage).isPositive();

        assertThat(accepted(page, perPage)).isFalse();
    }

    @Test
    void nonPositivePagesAreRejectedRatherThanSilentlyClampedToOne() {
        assertThat(accepted(0, 30)).isFalse();
        assertThat(accepted(-1, 30)).isFalse();
    }

    @Test
    void ordinaryPaginationStillWorks() {
        assertThat(accepted(1, 30)).isTrue();
        assertThat(accepted(2, 30)).isTrue();
        assertThat(accepted(10, 30)).isTrue();
        assertThat(accepted(MAX_PAGE, 30)).isTrue();   // 99*30 = 2970 <= 5000
    }

    @Test
    void theSkipCapBindsBeforeThePageCapAtLargePerPage() {
        // page 100 at per_page 100 is 9900 skipped commits — under the page
        // limit but over the skip limit, which is why both bounds exist.
        assertThat(accepted(MAX_PAGE, MAX_PER_PAGE)).isFalse();
        assertThat(accepted(51, MAX_PER_PAGE)).isTrue();   // 5000, exactly at the cap
        assertThat(accepted(52, MAX_PER_PAGE)).isFalse();  // 5100
    }

    // ── repo / sha shape ────────────────────────────────────────────────────

    @Test
    void repoMustBeOwnerSlashRepo() {
        assertThat(OWNER_REPO.matcher("bettercallok/git-oracle").matches()).isTrue();
        assertThat(OWNER_REPO.matcher("o/r").matches()).isTrue();
        assertThat(OWNER_REPO.matcher("my.org/my-repo_2").matches()).isTrue();
    }

    @Test
    void theOldContainsSlashCheckAdmittedTraversal() {
        // `repo` is interpolated into a GitHub API request path, so a traversing
        // value addresses a different API resource. "contains a slash" allowed
        // every one of these.
        for (String bad : new String[] {
            "../../some/other/path",
            "owner/repo/../../admin",
            "/absolute/path",
            "owner//repo",
            "owner/repo?x=1",
            "owner/repo#frag",
            "owner with space/repo",
            "a".repeat(101) + "/repo",
        }) {
            assertThat(bad.contains("/")).as("old check admitted %s", bad).isTrue();
            assertThat(OWNER_REPO.matcher(bad).matches()).as("new check must reject %s", bad).isFalse();
        }
    }

    @Test
    void repoWithoutASlashIsStillRejected() {
        assertThat(OWNER_REPO.matcher("justaname").matches()).isFalse();
        assertThat(OWNER_REPO.matcher("").matches()).isFalse();
    }
}
