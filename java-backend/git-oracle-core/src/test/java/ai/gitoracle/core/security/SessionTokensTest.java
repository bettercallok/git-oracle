package ai.gitoracle.core.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SessionTokensTest {

    @Test
    void generatedTokensAreWellFormedUniqueAndOnlyTheirHashIsReturnedAsTheStoredValue() {
        SessionTokens.GeneratedToken a = SessionTokens.generate();
        SessionTokens.GeneratedToken b = SessionTokens.generate();

        assertThat(SessionTokens.isWellFormed(a.token())).isTrue();
        assertThat(a.token()).isNotEqualTo(b.token());
        assertThat(a.hash()).isEqualTo(SessionTokens.hash(a.token())).isNotEqualTo(a.token());
    }

    @Test
    void anythingElseIsNotWellFormed() {
        assertThat(SessionTokens.isWellFormed(null)).isFalse();
        assertThat(SessionTokens.isWellFormed("")).isFalse();
        assertThat(SessionTokens.isWellFormed("abc123")).isFalse();
        assertThat(SessionTokens.isWellFormed("Z".repeat(64))).isFalse();
        assertThat(SessionTokens.isWellFormed("a".repeat(65))).isFalse();
    }
}
