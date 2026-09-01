package ai.gitoracle.ingestor.security;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * Verifies an HMAC-SHA256 webhook signature against the exact raw request
 * body. Both webhook endpoints on WebhookController used to accept and act
 * on any POST with no verification at all — GITHUB_APP_WEBHOOK_SECRET and
 * SENTRY_WEBHOOK_SECRET were both defined in .env.example and never read
 * anywhere in the codebase. Verification MUST run against the raw bytes as
 * received, before any JSON parsing: re-serializing a parsed Map is not
 * guaranteed to byte-for-byte match what the sender actually signed (key
 * ordering, whitespace, number formatting can all differ), which would make
 * every legitimate signature fail to verify.
 */
public final class HmacVerifier {

    private HmacVerifier() {}

    private static final String ALGORITHM = "HmacSHA256";

    /**
     * @param rawBody         the exact bytes the sender computed their signature over
     * @param secret          the shared secret configured for this integration
     * @param signatureHeader the header value as received, e.g. "sha256=<hex>"
     * @param prefix          the expected prefix, e.g. "sha256="
     * @return true only if secret is configured, the header is present and
     *         well-formed, and the computed HMAC matches in constant time
     */
    public static boolean verify(byte[] rawBody, String secret, String signatureHeader, String prefix) {
        if (secret == null || secret.isBlank()) return false;
        if (signatureHeader == null || !signatureHeader.startsWith(prefix)) return false;

        String providedHex = signatureHeader.substring(prefix.length()).trim();
        String computedHex;
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            computedHex = HexFormat.of().formatHex(mac.doFinal(rawBody == null ? new byte[0] : rawBody));
        } catch (Exception e) {
            return false;
        }

        // Constant-time compare, same idiom as InternalAuthFilter's token
        // check — a naive String.equals here would leak timing information
        // about how many leading hex characters matched.
        return MessageDigest.isEqual(
            computedHex.getBytes(StandardCharsets.UTF_8),
            providedHex.toLowerCase().getBytes(StandardCharsets.UTF_8)
        );
    }
}
