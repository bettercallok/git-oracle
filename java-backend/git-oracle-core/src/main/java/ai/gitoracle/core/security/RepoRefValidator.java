package ai.gitoracle.core.security;

import java.net.URI;
import java.util.Collection;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Validates repository references (clone URLs, branch names, commit SHAs)
 * before they reach a `git` invocation anywhere in the pipeline.
 *
 * repoUrl and branch flow from HTTP request bodies and LLM output straight
 * into ProcessBuilder/JGit clone commands (test-runner, github-bot,
 * orchestrator) with no validation. A repoUrl of `ext::sh -c '<cmd>'`
 * (git's ext:: transport) executes arbitrary commands, and a value starting
 * with `-` is parsed as a git option (e.g. `--upload-pack=...`) rather than
 * a repository. Every git-invoking call site must run its inputs through
 * this validator first.
 */
public final class RepoRefValidator {

    private RepoRefValidator() {}

    public static final List<String> DEFAULT_ALLOWED_HOSTS = List.of("github.com");

    private static final int MAX_URL_LENGTH = 512;
    private static final int MAX_BRANCH_LENGTH = 255;

    // Loosely mirrors GitHub's own owner/repo naming — this only needs to
    // reject dangerous characters, not perfectly replicate GitHub's rules.
    private static final Pattern OWNER_REPO = Pattern.compile("[A-Za-z0-9._-]{1,100}/[A-Za-z0-9._-]{1,100}");
    private static final Pattern BRANCH_PATTERN = Pattern.compile("[A-Za-z0-9._/-]{1,255}");
    private static final Pattern SHA_PATTERN = Pattern.compile("[0-9a-fA-F]{7,40}");

    public static final class InvalidRepoRefException extends RuntimeException {
        public InvalidRepoRefException(String message) {
            super(message);
        }
    }

    /** Validates against the default host allowlist (github.com only). */
    public static String validateRepoUrl(String repoUrl) {
        return validateRepoUrl(repoUrl, DEFAULT_ALLOWED_HOSTS);
    }

    public static String validateRepoUrl(String repoUrl, Collection<String> allowedHosts) {
        if (repoUrl == null || repoUrl.isBlank()) {
            throw new InvalidRepoRefException("repoUrl must not be blank");
        }
        String trimmed = repoUrl.trim();
        if (trimmed.length() > MAX_URL_LENGTH) {
            throw new InvalidRepoRefException("repoUrl exceeds maximum length of " + MAX_URL_LENGTH);
        }
        if (containsControlChars(trimmed)) {
            throw new InvalidRepoRefException("repoUrl contains control characters");
        }
        // Git's alternate transports (ext::, fd::) can execute arbitrary
        // commands. A legitimate https URL never contains "::".
        if (trimmed.contains("::")) {
            throw new InvalidRepoRefException("repoUrl uses a forbidden transport");
        }

        URI uri;
        try {
            uri = new URI(trimmed);
        } catch (Exception e) {
            throw new InvalidRepoRefException("repoUrl is not a valid URI");
        }

        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            throw new InvalidRepoRefException("repoUrl must use the https scheme");
        }
        if (uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
            throw new InvalidRepoRefException("repoUrl must not contain userinfo, query, or fragment components");
        }
        if (uri.getPort() != -1) {
            throw new InvalidRepoRefException("repoUrl must not specify an explicit port");
        }
        String host = uri.getHost();
        if (host == null || allowedHosts.stream().noneMatch(host::equalsIgnoreCase)) {
            throw new InvalidRepoRefException("repoUrl host is not in the allowed list: " + allowedHosts);
        }

        String path = uri.getRawPath() == null ? "" : uri.getRawPath();
        String pathNoLeadingSlash = path.startsWith("/") ? path.substring(1) : path;
        String pathNoGitSuffix = pathNoLeadingSlash.endsWith(".git")
            ? pathNoLeadingSlash.substring(0, pathNoLeadingSlash.length() - 4)
            : pathNoLeadingSlash;
        if (!OWNER_REPO.matcher(pathNoGitSuffix).matches()) {
            throw new InvalidRepoRefException("repoUrl path must be in owner/repo form");
        }
        return trimmed;
    }

    /** Returns null for a blank/null branch — callers treat that as "use the default branch". */
    public static String validateBranch(String branch) {
        if (branch == null || branch.isBlank()) {
            return null;
        }
        String trimmed = branch.trim();
        if (trimmed.length() > MAX_BRANCH_LENGTH) {
            throw new InvalidRepoRefException("branch exceeds maximum length of " + MAX_BRANCH_LENGTH);
        }
        if (containsControlChars(trimmed)) {
            throw new InvalidRepoRefException("branch contains control characters");
        }
        if (trimmed.startsWith("-")) {
            throw new InvalidRepoRefException("branch must not start with '-'");
        }
        if (trimmed.contains("..") || trimmed.contains("//") || trimmed.contains("@{")
                || trimmed.endsWith(".lock") || trimmed.endsWith("/") || trimmed.endsWith(".")) {
            throw new InvalidRepoRefException("branch is not a valid git ref name");
        }
        if (!BRANCH_PATTERN.matcher(trimmed).matches()) {
            throw new InvalidRepoRefException("branch contains forbidden characters");
        }
        return trimmed;
    }

    /** Returns null for a blank/null sha. */
    public static String validateSha(String sha) {
        if (sha == null || sha.isBlank()) {
            return null;
        }
        String trimmed = sha.trim();
        if (!SHA_PATTERN.matcher(trimmed).matches()) {
            throw new InvalidRepoRefException("commit sha is not a valid hex string of 7-40 characters");
        }
        return trimmed;
    }

    private static boolean containsControlChars(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (Character.isISOControl(s.charAt(i))) {
                return true;
            }
        }
        return false;
    }
}
