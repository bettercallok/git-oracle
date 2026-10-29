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
        // Root .env (exported by start_local.sh) first; the legacy github-bot/.env
        // — this process's working directory — second. See GitHubAppCredentials.
        GitHubAppCredentials creds = GitHubAppCredentials.load(
            System.getProperty("user.dir") + "/.env", logger);
        this.appId = creds.appId();
        this.installationId = creds.installationId();
        this.privateKeyPath = creds.privateKeyPath();
        logger.info("Initialized GitHubClient for App ID: {} (from {})", this.appId, creds.source());
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
