package ai.gitoracle.orchestrator.service;

import org.slf4j.Logger;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * Where the GitHub App credentials come from.
 *
 * <p>They used to live only in a second file, {@code java-backend/github-bot/.env},
 * read by path: start_local.sh exports the root .env into every process, and
 * that file's placeholders ({@code your_github_app_id}) shadowed the real values
 * whenever a reader preferred the environment. The fix at the time was to stop
 * reading the environment at all, which left two .env files to keep in sync.
 *
 * <p>Now the environment (the one root .env) wins whenever it holds real values
 * — blanks and {@code your_*} placeholders don't count — and all three values are
 * taken from the same source, so an ID from one file is never paired with a key
 * from the other. The old file still works, with a deprecation warning.
 *
 * <p>github-bot has no dependency on git-oracle-core, so it keeps an identical
 * copy of this class ({@code com.gitoracle.githubbot.GitHubAppCredentials}).
 */
public record GitHubAppCredentials(String appId, String installationId, String privateKeyPath, String source) {

    public static final String APP_ID = "GITHUB_APP_ID";
    public static final String INSTALLATION_ID = "GITHUB_INSTALLATION_ID";
    public static final String PRIVATE_KEY_PATH = "GITHUB_PRIVATE_KEY_PATH";

    public static GitHubAppCredentials load(String legacyEnvFile, Logger logger) {
        GitHubAppCredentials creds = load(System::getenv, legacyEnvFile, logger);
        // Opt-in hard requirement (the CI eval sets it): without it, missing
        // credentials only surface when the first PR fails to open.
        if (!creds.isConfigured() && "true".equalsIgnoreCase(System.getenv("GITORACLE_REQUIRE_GITHUB_APP"))) {
            throw new IllegalStateException("GITORACLE_REQUIRE_GITHUB_APP=true but GITHUB_APP_ID, "
                + "GITHUB_INSTALLATION_ID and GITHUB_PRIVATE_KEY_PATH are not all set.");
        }
        return creds;
    }

    static GitHubAppCredentials load(Function<String, String> env, String legacyEnvFile, Logger logger) {
        if (usable(env.apply(APP_ID))) {
            return new GitHubAppCredentials(env.apply(APP_ID), env.apply(INSTALLATION_ID),
                                            env.apply(PRIVATE_KEY_PATH), "environment");
        }
        Map<String, String> legacy = readEnvFile(legacyEnvFile);
        if (usable(legacy.get(APP_ID))) {
            logger.warn("GitHub App credentials read from {} — deprecated. Move GITHUB_APP_ID, "
                        + "GITHUB_INSTALLATION_ID and GITHUB_PRIVATE_KEY_PATH into the root .env.", legacyEnvFile);
            return new GitHubAppCredentials(legacy.get(APP_ID), legacy.get(INSTALLATION_ID),
                                            legacy.get(PRIVATE_KEY_PATH), legacyEnvFile);
        }
        logger.warn("No GitHub App credentials configured — opening PRs and GitHub API calls will fail. "
                    + "Set GITHUB_APP_ID, GITHUB_INSTALLATION_ID and GITHUB_PRIVATE_KEY_PATH in the root .env.");
        return new GitHubAppCredentials(null, null, null, "none");
    }

    public boolean isConfigured() {
        return usable(appId) && usable(installationId) && usable(privateKeyPath);
    }

    static boolean usable(String value) {
        return value != null && !value.isBlank() && !value.startsWith("your_");
    }

    private static Map<String, String> readEnvFile(String path) {
        Map<String, String> values = new HashMap<>();
        if (path == null) return values;
        try {
            for (String line : Files.readAllLines(Paths.get(path), StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#") || !trimmed.contains("=")) continue;
                int idx = trimmed.indexOf('=');
                values.put(trimmed.substring(0, idx).trim(), trimmed.substring(idx + 1).trim());
            }
        } catch (java.io.IOException e) {
            // Expected once the credentials have moved to the root .env.
        }
        return values;
    }
}
