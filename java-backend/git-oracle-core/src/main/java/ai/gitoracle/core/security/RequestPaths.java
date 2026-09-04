package ai.gitoracle.core.security;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Canonicalises a request path before it is matched against a security rule.
 *
 * <h2>Why this exists</h2>
 * Both the api-gateway and the orchestrator gated {@code /api/v1/admin/**} with
 * a bare {@code path.startsWith("/api/v1/admin")} against the <em>raw</em>
 * request path. The router does not see that same string: the servlet container
 * strips path parameters and percent-decodes before matching a controller, and
 * Spring Cloud Gateway's predicates normalise differently again. Any difference
 * between "the string the security filter tested" and "the string the router
 * dispatched on" is an authorization bypass, and two of them were live:
 *
 * <pre>
 *   POST /api/v1/%61dmin/tenants     %61 is 'a'. The filter saw "%61dmin" and
 *                                    did not match; Spring decoded it and
 *                                    dispatched to AdminController.
 *
 *   POST /api/v1/admin;x=1/tenants   Tomcat strips the ";x=1" path parameter
 *                                    before mapping; the filter tested the
 *                                    string with it still attached.
 * </pre>
 *
 * Both created a tenant with no {@code platform:admin} scope — the second one
 * through the gateway, using an ordinary tenant-scoped API key, i.e. reachable
 * by any authenticated external caller.
 *
 * <h2>Deliberately aggressive, because this feeds a DENY decision</h2>
 * Every transformation here can only make a path <em>more</em> likely to match
 * a protected prefix, never less. For a rule that denies, over-matching costs
 * an unnecessary 403 on a deliberately weird path; under-matching is a bypass.
 * That asymmetry is why decoding is applied repeatedly until the value stops
 * changing rather than exactly once: no layer in this stack is known to
 * double-decode, but if one ever does, the failure lands on the safe side.
 *
 * <p><b>Do not use this to match an allowlist.</b> For a rule that grants
 * (an authentication-exempt path, say) the asymmetry inverts and this
 * normalisation becomes the wrong tool — see
 * {@link #isCanonical(String)}, which callers should use to require that an
 * exempt path arrived in already-canonical form instead.
 */
public final class RequestPaths {

    /** Bounds the decode loop; also the point at which a path is simply hostile. */
    private static final int MAX_DECODE_PASSES = 4;

    private RequestPaths() {}

    /**
     * @return the path as a router would resolve it: percent-decoded, path
     *         parameters removed, duplicate slashes collapsed, {@code .} and
     *         {@code ..} segments resolved. Always starts with {@code /}.
     */
    public static String canonicalize(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) return "/";

        String decoded = decodeRepeatedly(rawPath);
        String withoutParams = stripPathParameters(decoded);
        return resolveDotSegments(withoutParams);
    }

    /**
     * True if {@code rawPath} is already exactly what {@link #canonicalize}
     * would produce — i.e. it contains no encoding, path parameters, empty
     * segments, or dot segments to unwrap.
     *
     * <p>This is what an <em>allowlist</em> check should require. An exempt-path
     * rule that canonicalises first would treat {@code /actuator/../api/v1/admin}
     * as exempt on the strength of its harmless-looking prefix; requiring the
     * path to arrive already canonical means anything ambiguous simply fails the
     * exemption and proceeds to normal authentication.
     */
    public static boolean isCanonical(String rawPath) {
        if (rawPath == null) return false;
        return rawPath.equals(canonicalize(rawPath));
    }

    /**
     * True if {@code rawPath} canonicalises to {@code prefix} itself or to
     * something beneath it as a whole path segment.
     *
     * <p>The segment boundary matters independently of the normalisation above:
     * a plain prefix test also matches {@code /api/v1/adminXYZ}, which is a
     * different route entirely.
     */
    public static boolean isUnderPrefix(String rawPath, String prefix) {
        String canonical = canonicalize(rawPath);
        return canonical.equals(prefix) || canonical.startsWith(prefix + "/");
    }

    private static String decodeRepeatedly(String path) {
        String current = path;
        for (int i = 0; i < MAX_DECODE_PASSES; i++) {
            if (current.indexOf('%') < 0) return current;
            String next;
            try {
                next = URLDecoder.decode(current, StandardCharsets.UTF_8);
            } catch (IllegalArgumentException e) {
                // Malformed escape sequence. Leave it as-is: it cannot decode
                // into anything, and the router will reject the request too.
                return current;
            }
            if (next.equals(current)) return current;
            current = next;
        }
        return current;
    }

    /**
     * Removes {@code ;name=value} path parameters from every segment. Tomcat
     * does this before mapping a request to a controller, so a filter that does
     * not is testing a string the router will never see.
     */
    private static String stripPathParameters(String path) {
        if (path.indexOf(';') < 0) return path;

        StringBuilder out = new StringBuilder(path.length());
        boolean skipping = false;
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c == ';') {
                skipping = true;
            } else if (c == '/') {
                skipping = false;
                out.append(c);
            } else if (!skipping) {
                out.append(c);
            }
        }
        return out.toString();
    }

    /** Collapses empty segments and resolves {@code .} / {@code ..}. */
    private static String resolveDotSegments(String path) {
        Deque<String> stack = new ArrayDeque<>();
        for (String segment : path.split("/")) {
            if (segment.isEmpty() || segment.equals(".")) {
                continue;
            }
            if (segment.equals("..")) {
                stack.pollLast();
                continue;
            }
            stack.addLast(segment);
        }

        if (stack.isEmpty()) return "/";
        StringBuilder out = new StringBuilder();
        for (String segment : stack) {
            out.append('/').append(segment);
        }
        // A trailing slash is not meaningful to any rule here, and keeping it
        // would make "/api/v1/admin/" and "/api/v1/admin" compare unequal.
        return out.toString();
    }
}
