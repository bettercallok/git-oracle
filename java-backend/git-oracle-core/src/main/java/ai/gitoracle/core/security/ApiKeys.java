package ai.gitoracle.core.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * Format, generation, and verification for GitOracle API keys.
 *
 * Deliberately free of Spring and JPA imports: the api-gateway is a WebFlux
 * application with no JPA on its classpath and looks keys up over plain JDBC,
 * while the orchestrator mints them through JPA. Both need to agree byte-for-byte
 * on the key format and the hashing scheme, so that agreement lives here in
 * git-oracle-core rather than being independently re-implemented on each side.
 *
 * <h2>Key format</h2>
 * <pre>gor_&lt;12 hex chars prefix&gt;_&lt;64 hex chars secret&gt;</pre>
 *
 * The prefix exists so a key can be located in the database with a single
 * indexed equality lookup. Without it the only way to find the matching row
 * would be to hash the presented key and query by hash — which works, but means
 * the lookup column is the secret-equivalent value itself, and it makes
 * displaying a partial key in a management UI ("gor_a1b2c3d4e5f6…") impossible.
 * The prefix is public information; it authorises nothing on its own.
 *
 * <h2>Why plain SHA-256 and not bcrypt/argon2</h2>
 * Password hashing algorithms are deliberately slow because human-chosen
 * passwords have low entropy and must survive an offline brute-force of the
 * stolen hash. That does not apply here: the secret half is 32 bytes from
 * {@link SecureRandom}, i.e. 256 bits of entropy, so brute-forcing it is
 * infeasible regardless of how fast the hash is. Using bcrypt here would buy
 * nothing and would cost a deliberately-expensive KDF on the hot path of every
 * single API request. What matters — and what this class does provide — is that
 * the stored value is a one-way hash (a database read does not yield usable
 * credentials) and that comparison is constant-time.
 */
public final class ApiKeys {

    private static final String MARKER = "gor";
    private static final int PREFIX_HEX_CHARS = 12;
    private static final int SECRET_BYTES = 32;

    /** Guards against hashing an unbounded attacker-supplied string. */
    private static final int MAX_PRESENTED_LENGTH = 256;

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final HexFormat HEX = HexFormat.of();

    private ApiKeys() {}

    /**
     * A freshly minted key. {@code presented} is the only time the full key is
     * ever available — it is not recoverable from {@code hash}, so a caller that
     * does not return it to the user has permanently lost it. That is the
     * intended property.
     */
    public record GeneratedKey(String presented, String prefix, String hash) {}

    public static GeneratedKey generate() {
        byte[] prefixBytes = new byte[PREFIX_HEX_CHARS / 2];
        byte[] secretBytes = new byte[SECRET_BYTES];
        RANDOM.nextBytes(prefixBytes);
        RANDOM.nextBytes(secretBytes);

        String prefix = HEX.formatHex(prefixBytes);
        String presented = MARKER + "_" + prefix + "_" + HEX.formatHex(secretBytes);
        return new GeneratedKey(presented, prefix, hash(presented));
    }

    /**
     * The indexed lookup value for a presented key, or {@code null} if the string
     * is not a well-formed GitOracle key at all.
     *
     * <p>Returning null rather than throwing keeps the caller's control flow flat:
     * a malformed key and an unknown key are the same outcome (reject), and the
     * caller must not be able to distinguish them in its response either.
     */
    public static String extractPrefix(String presented) {
        if (presented == null || presented.length() > MAX_PRESENTED_LENGTH) return null;

        String[] parts = presented.split("_");
        if (parts.length != 3) return null;
        if (!MARKER.equals(parts[0])) return null;
        if (parts[1].length() != PREFIX_HEX_CHARS) return null;
        if (!isHex(parts[1]) || !isHex(parts[2])) return null;

        return parts[1];
    }

    /** True if the string is shaped like a GitOracle key, regardless of whether it exists. */
    public static boolean looksLikeApiKey(String presented) {
        return extractPrefix(presented) != null;
    }

    public static String hash(String presented) {
        if (presented == null) throw new IllegalArgumentException("presented key must not be null");
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            return HEX.formatHex(sha256.digest(presented.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by the JLS on every conforming JVM.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * Constant-time comparison of a presented key against a stored hash.
     *
     * <p>This is the H10 fix. The previous single shared key was compared with
     * {@code String.equals}, which short-circuits on the first differing byte and
     * so leaks, through response timing, how many leading characters of a guess
     * were correct — enough to recover a secret byte by byte given enough
     * samples. {@link MessageDigest#isEqual} compares every byte regardless.
     *
     * <p>Both operands here are hex digests of identical length, so there is no
     * length side-channel either.
     */
    public static boolean matches(String presented, String storedHash) {
        if (presented == null || storedHash == null) return false;
        if (presented.length() > MAX_PRESENTED_LENGTH) return false;
        return constantTimeEquals(hash(presented), storedHash);
    }

    /**
     * Constant-time comparison of two secrets that are not key hashes — used for
     * the legacy single shared key, which has no stored hash to compare against.
     */
    public static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        return MessageDigest.isEqual(
            a.getBytes(StandardCharsets.UTF_8),
            b.getBytes(StandardCharsets.UTF_8));
    }

    private static boolean isHex(String s) {
        if (s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
            if (!hex) return false;
        }
        return true;
    }
}
