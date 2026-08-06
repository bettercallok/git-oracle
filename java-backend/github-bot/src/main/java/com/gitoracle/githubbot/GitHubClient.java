package com.gitoracle.githubbot;

import org.kohsuke.github.GitHub;
import org.kohsuke.github.GitHubBuilder;
import org.kohsuke.github.extras.authorization.JWTTokenProvider;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;

@Service
public class GitHubClient {
    private static final Logger logger = LoggerFactory.getLogger(GitHubClient.class);
    
    private final String appId;
    private final String installationId;
    private final String privateKeyPath;

    public GitHubClient() {
        // Read github-bot/.env directly rather than via Dotenv.configure().load():
        // dotenv-java prefers an already-set OS environment variable of the same
        // name over the file it just loaded. Any process launched after `set -a;
        // source .env` on the workspace-root .env (e.g. start_local.sh, or a
        // manual restart during dev) inherits the root .env's placeholder
        // GITHUB_APP_ID/etc as real env vars, which then silently shadow this
        // file's real values regardless of CWD. Confirmed live: "Initialized
        // GitHubClient for App ID: your_github_app_id" after exactly that restart
        // pattern — the same bug already fixed once for the orchestrator's
        // GitHubClientService.
        java.util.Map<String, String> botEnv = readEnvFile(
            System.getProperty("user.dir") + "/.env");
        this.appId = botEnv.get("GITHUB_APP_ID");
        this.installationId = botEnv.get("GITHUB_INSTALLATION_ID");
        this.privateKeyPath = botEnv.get("GITHUB_PRIVATE_KEY_PATH");
        logger.info("Initialized GitHubClient for App ID: {}", this.appId);
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

    public String getLatestInstallationToken() throws Exception {
        String keyContent = new String(Files.readAllBytes(Paths.get(privateKeyPath)), StandardCharsets.UTF_8)
            .replaceAll("-----BEGIN PRIVATE KEY-----", "")
            .replaceAll("-----END PRIVATE KEY-----", "")
            .replaceAll("\\s+", "");
        
        PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(Base64.getDecoder().decode(keyContent));
        KeyFactory kf = KeyFactory.getInstance("RSA");
        PrivateKey privateKey = kf.generatePrivate(keySpec);

        long nowMillis = System.currentTimeMillis();
        long expMillis = nowMillis + (9 * 60 * 1000);

        String headerJson = "{\"alg\":\"RS256\",\"typ\":\"JWT\"}";
        String payloadJson = "{\"iat\":" + (nowMillis / 1000) + ",\"exp\":" + (expMillis / 1000) + ",\"iss\":" + appId + "}";

        String headerB64 = Base64.getUrlEncoder().withoutPadding().encodeToString(headerJson.getBytes(StandardCharsets.UTF_8));
        String payloadB64 = Base64.getUrlEncoder().withoutPadding().encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8));

        String dataToSign = headerB64 + "." + payloadB64;

        java.security.Signature sig = java.security.Signature.getInstance("SHA256withRSA");
        sig.initSign(privateKey);
        sig.update(dataToSign.getBytes(StandardCharsets.UTF_8));
        byte[] signatureBytes = sig.sign();

        String signatureB64 = Base64.getUrlEncoder().withoutPadding().encodeToString(signatureBytes);
        String jwt = dataToSign + "." + signatureB64;

        GitHub appClient = new GitHubBuilder().withJwtToken(jwt).build();
        return appClient.getApp().getInstallationById(Long.parseLong(installationId)).createToken().create().getToken();
    }

    public GitHub getAuthenticatedGitHub() throws Exception {
        String installationToken = getLatestInstallationToken();
        return new GitHubBuilder().withAppInstallationToken(installationToken).build();
    }
}
