package ai.gitoracle.orchestrator.service;

import io.github.cdimascio.dotenv.Dotenv;
import org.kohsuke.github.GitHub;
import org.kohsuke.github.GitHubBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;

/**
 * Shared GitHub App authentication service for the Orchestrator.
 *
 * Reads credentials from the same .env file used by the github-bot module
 * (GITHUB_APP_ID, GITHUB_INSTALLATION_ID, GITHUB_PRIVATE_KEY_PATH).
 *
 * The installation token is refreshed on every call to avoid expiry issues.
 * GitHub installation tokens are valid for 1 hour.
 */
@Service
public class GitHubClientService {

    private static final Logger logger = LoggerFactory.getLogger(GitHubClientService.class);

    private final String appId;
    private final String installationId;
    private final String privateKeyPath;

    public GitHubClientService() {
        // Read github-bot/.env directly rather than via Dotenv.configure(), because
        // dotenv-java's get() prefers an already-set OS environment variable of the
        // same name over the file it just loaded. start_local.sh does `set -a; source
        // .env` on the workspace-root .env before launching this process, so the root
        // .env's placeholder values (e.g. GITHUB_PRIVATE_KEY_PATH=./secrets/github-app.pem)
        // were always winning over github-bot/.env's real ones, regardless of which
        // .directory() this pointed at — confirmed live via the Commit Explorer failing
        // with "GitHub API error: ./secrets/github-app.pem".
        java.util.Map<String, String> botEnv = readEnvFile("/Users/omkhatri/Git Oracle/java-backend/github-bot/.env");
        this.appId           = botEnv.get("GITHUB_APP_ID");
        this.installationId  = botEnv.get("GITHUB_INSTALLATION_ID");
        this.privateKeyPath  = botEnv.get("GITHUB_PRIVATE_KEY_PATH");
        logger.info("GitHubClientService initialised for App ID: {}", appId);
    }

    private static java.util.Map<String, String> readEnvFile(String path) {
        java.util.Map<String, String> values = new java.util.HashMap<>();
        try {
            for (String line : Files.readAllLines(Paths.get(path), StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#") || !trimmed.contains("=")) {
                    continue;
                }
                int idx = trimmed.indexOf('=');
                values.put(trimmed.substring(0, idx).trim(), trimmed.substring(idx + 1).trim());
            }
        } catch (java.io.IOException e) {
            logger.warn("Could not read {}: {}", path, e.getMessage());
        }
        return values;
    }

    /**
     * Returns a fresh GitHub installation token (valid for ~1 hour).
     * Tokens are not cached — generate a fresh one per request to be safe.
     */
    public String getInstallationToken() throws Exception {
        String keyContent = new String(Files.readAllBytes(Paths.get(privateKeyPath)), StandardCharsets.UTF_8)
                .replaceAll("-----BEGIN PRIVATE KEY-----", "")
                .replaceAll("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s+", "");

        PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(Base64.getDecoder().decode(keyContent));
        KeyFactory kf = KeyFactory.getInstance("RSA");
        PrivateKey privateKey = kf.generatePrivate(keySpec);

        long nowMillis = System.currentTimeMillis();
        long expMillis = nowMillis + (9 * 60 * 1000); // 9 minutes, GitHub max is 10

        String headerJson  = "{\"alg\":\"RS256\",\"typ\":\"JWT\"}";
        String payloadJson = "{\"iat\":" + (nowMillis / 1000) + ",\"exp\":" + (expMillis / 1000) + ",\"iss\":" + appId + "}";

        String headerB64  = Base64.getUrlEncoder().withoutPadding().encodeToString(headerJson.getBytes(StandardCharsets.UTF_8));
        String payloadB64 = Base64.getUrlEncoder().withoutPadding().encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8));
        String dataToSign = headerB64 + "." + payloadB64;

        java.security.Signature sig = java.security.Signature.getInstance("SHA256withRSA");
        sig.initSign(privateKey);
        sig.update(dataToSign.getBytes(StandardCharsets.UTF_8));
        String sigB64 = Base64.getUrlEncoder().withoutPadding().encodeToString(sig.sign());
        String jwt = dataToSign + "." + sigB64;

        GitHub appClient = new GitHubBuilder().withJwtToken(jwt).build();
        String token = appClient.getApp()
                .getInstallationById(Long.parseLong(installationId))
                .createToken()
                .create()
                .getToken();
        logger.debug("Generated fresh installation token for App ID: {}", appId);
        return token;
    }

    /**
     * Returns an authenticated GitHub client using the installation token.
     */
    public GitHub getAuthenticatedClient() throws Exception {
        return new GitHubBuilder().withAppInstallationToken(getInstallationToken()).build();
    }
}
