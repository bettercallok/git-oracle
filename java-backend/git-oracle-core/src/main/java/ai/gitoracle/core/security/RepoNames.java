package ai.gitoracle.core.security;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One canonical name per GitHub repository: {@code owner/name}, lower-cased.
 *
 * <p>The per-tenant repository allow-list stores and compares repositories by
 * this slug, so every form a caller can name one in — {@code owner/name},
 * {@code https://github.com/Owner/Name}, with or without {@code .git} or a
 * trailing slash — resolves to the same entry. Anything else yields null and
 * is treated as not allowed.
 */
public final class RepoNames {

    private static final String PART = "[A-Za-z0-9_.-]+";
    private static final Pattern URL = Pattern.compile(
        "^https?://(?:www\\.)?github\\.com/(" + PART + ")/(" + PART + ")/?$",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern SLUG = Pattern.compile("^(" + PART + ")/(" + PART + ")$");

    private RepoNames() {}

    /** {@code owner/name} for a GitHub URL or slug, or null if it is neither. */
    public static String slug(String repo) {
        if (repo == null) {
            return null;
        }
        String value = repo.trim();
        Matcher m = URL.matcher(value);
        if (!m.matches()) {
            m = SLUG.matcher(value);
            if (!m.matches()) {
                return null;
            }
        }
        String name = m.group(2);
        if (name.toLowerCase(Locale.ROOT).endsWith(".git")) {
            name = name.substring(0, name.length() - 4);
        }
        if (name.isEmpty() || m.group(1).equals(".") || m.group(1).equals("..") || name.equals(".") || name.equals("..")) {
            return null;
        }
        return (m.group(1) + "/" + name).toLowerCase(Locale.ROOT);
    }
}
