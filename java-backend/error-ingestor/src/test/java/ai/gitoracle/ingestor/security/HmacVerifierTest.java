package ai.gitoracle.ingestor.security;

import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression tests for HmacVerifier, which is what actually authenticates
 * GitHub/Sentry webhook deliveries now that GITHUB_APP_WEBHOOK_SECRET and
 * SENTRY_WEBHOOK_SECRET are read for the first time (they existed in
 * .env.example and were verified nowhere in the codebase before this).
 */
class HmacVerifierTest {

    private static String realSignature(byte[] body, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body));
    }

    @Test
    void acceptsACorrectlyComputedSignature() throws Exception {
        byte[] body = "{\"event\":\"push\"}".getBytes(StandardCharsets.UTF_8);
        String secret = "shared-webhook-secret";
        String signature = realSignature(body, secret);

        assertThat(HmacVerifier.verify(body, secret, signature, "sha256=")).isTrue();
    }

    @Test
    void acceptsUppercaseHexTheSameAsLowercase() throws Exception {
        byte[] body = "{\"event\":\"push\"}".getBytes(StandardCharsets.UTF_8);
        String secret = "shared-webhook-secret";
        // Only the hex digest can plausibly vary in case — the "sha256="
        // prefix itself is a fixed literal GitHub always sends lowercase,
        // so uppercasing the whole header (prefix included) wouldn't be a
        // realistic case to guard against.
        String fullSignature = realSignature(body, secret);
        String hexOnly = fullSignature.substring("sha256=".length());
        String signature = "sha256=" + hexOnly.toUpperCase();

        assertThat(HmacVerifier.verify(body, secret, signature, "sha256=")).isTrue();
    }

    @Test
    void rejectsWhenSignedWithADifferentSecret() throws Exception {
        byte[] body = "{\"event\":\"push\"}".getBytes(StandardCharsets.UTF_8);
        String signature = realSignature(body, "attacker-guessed-secret");

        assertThat(HmacVerifier.verify(body, "real-secret", signature, "sha256=")).isFalse();
    }

    @Test
    void rejectsWhenTheBodyWasTamperedAfterSigning() throws Exception {
        String secret = "shared-webhook-secret";
        byte[] originalBody = "{\"amount\":1}".getBytes(StandardCharsets.UTF_8);
        String signature = realSignature(originalBody, secret);

        byte[] tamperedBody = "{\"amount\":9999}".getBytes(StandardCharsets.UTF_8);

        assertThat(HmacVerifier.verify(tamperedBody, secret, signature, "sha256=")).isFalse();
    }

    @Test
    void rejectsMissingSignatureHeader() {
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        assertThat(HmacVerifier.verify(body, "real-secret", null, "sha256=")).isFalse();
    }

    @Test
    void rejectsSignatureWithWrongPrefix() throws Exception {
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        String secret = "real-secret";
        String rawHexOnly = realSignature(body, secret).substring("sha256=".length());

        assertThat(HmacVerifier.verify(body, secret, rawHexOnly, "sha256=")).isFalse();
    }

    @Test
    void failsClosedWhenSecretIsUnconfigured() throws Exception {
        // Not computed from an empty secret (SecretKeySpec rejects a
        // zero-length key outright) — the point of this test is that verify()
        // rejects on the unconfigured-secret check before it would even
        // attempt to compute anything, so any well-formed header value works.
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        String signature = realSignature(body, "some-other-secret");

        assertThat(HmacVerifier.verify(body, "", signature, "sha256=")).isFalse();
        assertThat(HmacVerifier.verify(body, null, signature, "sha256=")).isFalse();
        assertThat(HmacVerifier.verify(body, "   ", signature, "sha256=")).isFalse();
    }

    @Test
    void treatsNullBodyAsEmptyRatherThanThrowing() throws Exception {
        String secret = "real-secret";
        String signature = realSignature(new byte[0], secret);

        assertThat(HmacVerifier.verify(null, secret, signature, "sha256=")).isTrue();
    }
}
