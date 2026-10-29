package ai.gitoracle.orchestrator.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The root .env must win when it holds real values, and must NOT win with the
 * .env.example placeholders it was copied from — that shadowing is why a second
 * .env existed in the first place.
 */
class GitHubAppCredentialsTest {

    private static final org.slf4j.Logger LOG = LoggerFactory.getLogger(GitHubAppCredentialsTest.class);

    private static Path legacyFile(Path dir) throws Exception {
        Path f = dir.resolve(".env");
        Files.writeString(f, "# legacy\nGITHUB_APP_ID=111\nGITHUB_INSTALLATION_ID=222\nGITHUB_PRIVATE_KEY_PATH=/legacy.pem\n");
        return f;
    }

    @Test
    void realValuesInTheEnvironmentWin(@TempDir Path dir) throws Exception {
        Map<String, String> env = Map.of("GITHUB_APP_ID", "4375929", "GITHUB_INSTALLATION_ID", "99",
                                         "GITHUB_PRIVATE_KEY_PATH", "/root/secrets/app.pem");

        var creds = GitHubAppCredentials.load(env::get, legacyFile(dir).toString(), LOG);

        assertThat(creds.appId()).isEqualTo("4375929");
        assertThat(creds.privateKeyPath()).isEqualTo("/root/secrets/app.pem");
        assertThat(creds.source()).isEqualTo("environment");
        assertThat(creds.isConfigured()).isTrue();
    }

    @Test
    void placeholdersInTheEnvironmentFallBackToTheLegacyFileAsAWhole(@TempDir Path dir) throws Exception {
        // What start_local.sh exported before anyone edited the copied .env.example.
        Map<String, String> env = Map.of("GITHUB_APP_ID", "your_github_app_id",
                                         "GITHUB_PRIVATE_KEY_PATH", "./secrets/github-app.pem");

        var creds = GitHubAppCredentials.load(env::get, legacyFile(dir).toString(), LOG);

        assertThat(creds.appId()).isEqualTo("111");
        // Never the env's key path paired with the file's id.
        assertThat(creds.privateKeyPath()).isEqualTo("/legacy.pem");
        assertThat(creds.source()).endsWith(".env");
    }

    @Test
    void blankEnvironmentValuesDontCount(@TempDir Path dir) throws Exception {
        var creds = GitHubAppCredentials.load(Map.of("GITHUB_APP_ID", " ")::get, legacyFile(dir).toString(), LOG);

        assertThat(creds.appId()).isEqualTo("111");
    }

    @Test
    void nothingConfiguredIsReportedAsSuch(@TempDir Path dir) {
        var creds = GitHubAppCredentials.load(Map.<String, String>of()::get, dir.resolve("missing.env").toString(), LOG);

        assertThat(creds.isConfigured()).isFalse();
        assertThat(creds.source()).isEqualTo("none");
    }
}
