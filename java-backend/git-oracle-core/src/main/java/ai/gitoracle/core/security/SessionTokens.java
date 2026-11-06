package ai.gitoracle.core.security;

import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * The opaque token in a signed-in browser's session cookie. Shared by the
 * orchestrator, which issues it, and the api-gateway, which resolves it.
 *
 * <p>Plain SHA-256 is enough for the same reason as {@link ApiKeys}: the token
 * is 256 random bits, not a human-chosen password. The row is found by that
 * digest, so a caller can't learn anything from comparison timing.
 */
public final class SessionTokens {

    public static final String COOKIE_NAME = "gitoracle_session";

    private static final int TOKEN_BYTES = 32;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final HexFormat HEX = HexFormat.of();

    private SessionTokens() {}

    public record GeneratedToken(String token, String hash) {}

    public static GeneratedToken generate() {
        byte[] bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        String token = HEX.formatHex(bytes);
        return new GeneratedToken(token, hash(token));
    }

    public static String hash(String token) {
        return ApiKeys.hash(token);
    }

    /** Lets the gateway reject garbage cookies without a database read. */
    public static boolean isWellFormed(String token) {
        return token != null && token.matches("[0-9a-f]{" + (TOKEN_BYTES * 2) + "}");
    }
}
