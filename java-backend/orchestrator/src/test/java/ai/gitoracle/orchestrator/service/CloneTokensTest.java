package ai.gitoracle.orchestrator.service;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Clone tokens: one repository, read only, reused until shortly before they expire. */
class CloneTokensTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-11-17T12:00:00Z");

    private final GitHubInstallations installations = mock(GitHubInstallations.class);
    private final List<String> minted = new ArrayList<>();

    private CloneTokens tokens(Clock clock) {
        return new CloneTokens(installations, (installation, repo) -> {
            minted.add(installation + ":" + repo);
            return new CloneTokens.Token("ghs_" + minted.size(), clock.instant().plus(Duration.ofHours(1)));
        }, clock);
    }

    @Test
    void aTokenIsForTheJobsRepositoryInTheTenantsInstallation() {
        when(installations.find(TENANT)).thenReturn(Optional.of("42"));

        var token = tokens(Clock.fixed(NOW, ZoneOffset.UTC))
            .forRepository(TENANT, "https://github.com/acme/private-app.git");

        assertThat(token).map(CloneTokens.Token::value).contains("ghs_1");
        assertThat(minted).containsExactly("42:private-app");
        assertThat(token.get().toString()).doesNotContain("ghs_");
    }

    @Test
    void oneTokenServesEveryCloneUntilShortlyBeforeItExpires() {
        when(installations.find(TENANT)).thenReturn(Optional.of("42"));
        MutableClock clock = new MutableClock(NOW);
        CloneTokens tokens = tokens(clock);

        tokens.forRepository(TENANT, "https://github.com/acme/app");
        clock.now = NOW.plus(Duration.ofMinutes(45));
        assertThat(tokens.valueFor(TENANT, "https://github.com/acme/app")).isEqualTo("ghs_1");

        clock.now = NOW.plus(Duration.ofMinutes(51));
        assertThat(tokens.valueFor(TENANT, "https://github.com/acme/app")).isEqualTo("ghs_2");
    }

    @Test
    void noInstallationOrAFailedMintMeansAnAnonymousClone() {
        when(installations.find(TENANT)).thenReturn(Optional.empty());
        CloneTokens tokens = tokens(Clock.fixed(NOW, ZoneOffset.UTC));
        assertThat(tokens.forRepository(TENANT, "https://github.com/acme/app")).isEmpty();

        UUID other = UUID.randomUUID();
        when(installations.find(other)).thenReturn(Optional.of("7"));
        CloneTokens failing = new CloneTokens(installations, (i, r) -> {
            throw new java.io.IOException("422 repository not in installation");
        }, Clock.fixed(NOW, ZoneOffset.UTC));
        assertThat(failing.forRepository(other, "https://github.com/acme/app")).isEmpty();
        assertThat(failing.forRepository(null, "https://github.com/acme/app")).isEmpty();
        assertThat(failing.forRepository(other, " ")).isEmpty();
    }

    private static final class MutableClock extends Clock {
        Instant now;
        MutableClock(Instant now) { this.now = now; }
        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
