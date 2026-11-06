package ai.gitoracle.orchestrator.service;

import io.github.cdimascio.dotenv.Dotenv;
import org.kohsuke.github.GHRepository;
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
import java.util.ArrayList;
import java.util.List;

/**
 * Shared GitHub App authentication service for the Orchestrator.
 *
 * Credentials: GITHUB_APP_ID, GITHUB_INSTALLATION_ID, GITHUB_PRIVATE_KEY_PATH,
 * resolved by GitHubAppCredentials (root .env first, legacy github-bot/.env second).
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

    @org.springframework.beans.factory.annotation.Value("${gitoracle.auth.github-api-url:https://api.github.com}")
    private String apiUrl = "https://api.github.com";

    public GitHubClientService() {
        // Root .env (exported by start_local.sh) first; the legacy github-bot/.env
        // second. start_local.sh launches this service from java-backend/, so the
        // default legacy path resolves to java-backend/github-bot/.env;
        // GITHUB_BOT_ENV_FILE overrides it. See GitHubAppCredentials for why.
        String botEnvFile = System.getenv().getOrDefault("GITHUB_BOT_ENV_FILE",
            System.getProperty("user.dir") + "/github-bot/.env");
        GitHubAppCredentials creds = GitHubAppCredentials.load(botEnvFile, logger);
        this.appId           = creds.appId();
        this.installationId  = creds.installationId();
        this.privateKeyPath  = creds.privateKeyPath();
        logger.info("GitHubClientService initialised for App ID: {} (from {})", appId, creds.source());
    }

    /**
     * Returns a fresh token for one installation (valid for ~1 hour).
     * Tokens are not cached — generate a fresh one per request to be safe.
     */
    public String getInstallationToken(String installationId) throws Exception {
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

        GitHub appClient = new GitHubBuilder().withEndpoint(apiUrl).withJwtToken(jwt).build();
        String token = appClient.getApp()
                .getInstallationById(Long.parseLong(installationId))
                .createToken()
                .create()
                .getToken();
        logger.debug("Generated fresh installation token for App ID: {}", appId);
        return token;
    }

    /** Returns a client acting as this installation. See {@link GitHubInstallations} for which one. */
    public GitHub getAuthenticatedClient(String installationId) throws Exception {
        return new GitHubBuilder().withEndpoint(apiUrl).withAppInstallationToken(getInstallationToken(installationId)).build();
    }

    /** The repositories this installation can see, as owner/name. */
    public List<String> repositoriesOf(String installationId) throws Exception {
        List<String> names = new ArrayList<>();
        for (GHRepository repo : getAuthenticatedClient(installationId).getInstallation().listRepositories()) {
            names.add(repo.getFullName());
        }
        return names;
    }

    /** GITHUB_INSTALLATION_ID: the default organisation's installation, and nobody else's. */
    public String defaultInstallationId() {
        return installationId;
    }
}
