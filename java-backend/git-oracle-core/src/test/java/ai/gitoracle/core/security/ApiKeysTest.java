package ai.gitoracle.core.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for the API key format and verification that replaced the single shared
 * secret compared with {@code String.equals}.
 */
class ApiKeysTest {

    @Test
    void generatesAKeyThatVerifiesAgainstItsOwnHash() {
        ApiKeys.GeneratedKey key = ApiKeys.generate();

        assertThat(ApiKeys.matches(key.presented(), key.hash())).isTrue();
    }

    @Test
    void generatedKeysAreUnique() {
        ApiKeys.GeneratedKey a = ApiKeys.generate();
        ApiKeys.GeneratedKey b = ApiKeys.generate();

        assertThat(a.presented()).isNotEqualTo(b.presented());
        assertThat(a.prefix()).isNotEqualTo(b.prefix());
        assertThat(a.hash()).isNotEqualTo(b.hash());
    }

    @Test
    void theStoredHashDoesNotContainTheKey() {
        // The whole point of storing a digest: a database dump must not yield
        // usable credentials.
        ApiKeys.GeneratedKey key = ApiKeys.generate();

        assertThat(key.hash()).doesNotContain(key.presented());
        assertThat(key.presented()).doesNotContain(key.hash());
    }

    @Test
    void prefixIsRecoverableFromThePresentedKey() {
        ApiKeys.GeneratedKey key = ApiKeys.generate();

        assertThat(ApiKeys.extractPrefix(key.presented())).isEqualTo(key.prefix());
    }

    @Test
    void aDifferentKeyDoesNotVerifyAgainstThisKeysHash() {
        ApiKeys.GeneratedKey real = ApiKeys.generate();
        ApiKeys.GeneratedKey other = ApiKeys.generate();

        assertThat(ApiKeys.matches(other.presented(), real.hash())).isFalse();
    }

    @Test
    void aKeyWithTheRightPrefixButWrongSecretIsRejected() {
        // The prefix is public and only locates the row — it must not be
        // sufficient on its own to authenticate.
        ApiKeys.GeneratedKey real = ApiKeys.generate();
        String forged = "gor_" + real.prefix() + "_" + "a".repeat(64);

        assertThat(ApiKeys.extractPrefix(forged)).isEqualTo(real.prefix());
        assertThat(ApiKeys.matches(forged, real.hash())).isFalse();
    }

    @Test
    void malformedKeysYieldNoPrefixRatherThanThrowing() {
        assertThat(ApiKeys.extractPrefix(null)).isNull();
        assertThat(ApiKeys.extractPrefix("")).isNull();
        assertThat(ApiKeys.extractPrefix("not-a-key")).isNull();
        assertThat(ApiKeys.extractPrefix("gor_tooshort_abc")).isNull();
        assertThat(ApiKeys.extractPrefix("wrong_aabbccddeeff_" + "a".repeat(64))).isNull();
        assertThat(ApiKeys.extractPrefix("gor_zzzzzzzzzzzz_" + "a".repeat(64))).isNull();  // not hex
        assertThat(ApiKeys.extractPrefix("gor_aabbccddeeff")).isNull();                     // missing secret
        assertThat(ApiKeys.extractPrefix("gor_aabbccddeeff_abc_extra")).isNull();           // too many parts
    }

    @Test
    void looksLikeApiKeyDistinguishesRealFormatFromLegacySecret() {
        // This is what routes a presented credential to the database lookup
        // versus the legacy shared-secret comparison, so it has to be exact.
        assertThat(ApiKeys.looksLikeApiKey(ApiKeys.generate().presented())).isTrue();
        assertThat(ApiKeys.looksLikeApiKey("dvcgh7Yt9Ll2dMIFbuJXAKgCtXGfj8gSELGCsemwEQQ")).isFalse();
    }

    @Test
    void anAbsurdlyLongPresentedKeyIsRejectedWithoutBeingHashed() {
        String huge = "gor_aabbccddeeff_" + "a".repeat(100_000);

        assertThat(ApiKeys.extractPrefix(huge)).isNull();
        assertThat(ApiKeys.matches(huge, ApiKeys.generate().hash())).isFalse();
    }

    @Test
    void matchesIsNullSafeInBothDirections() {
        assertThat(ApiKeys.matches(null, "somehash")).isFalse();
        assertThat(ApiKeys.matches("gor_aabbccddeeff_" + "a".repeat(64), null)).isFalse();
        assertThat(ApiKeys.matches(null, null)).isFalse();
    }

    @Test
    void constantTimeEqualsBehavesLikeEqualsForTheLegacySecret() {
        assertThat(ApiKeys.constantTimeEquals("secret", "secret")).isTrue();
        assertThat(ApiKeys.constantTimeEquals("secret", "secreT")).isFalse();
        assertThat(ApiKeys.constantTimeEquals("secret", "secret-longer")).isFalse();
        assertThat(ApiKeys.constantTimeEquals("", "")).isTrue();
        assertThat(ApiKeys.constantTimeEquals(null, "secret")).isFalse();
        assertThat(ApiKeys.constantTimeEquals("secret", null)).isFalse();
    }

    @Test
    void hashingRejectsNullRatherThanSilentlyProducingADigest() {
        assertThatThrownBy(() -> ApiKeys.hash(null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void hashIsStableAcrossCalls() {
        // The gateway compares a freshly computed hash against one stored days
        // earlier by the orchestrator; if this were not stable, every key would
        // stop working at some point after it was minted.
        String presented = ApiKeys.generate().presented();

        assertThat(ApiKeys.hash(presented)).isEqualTo(ApiKeys.hash(presented));
    }
}
