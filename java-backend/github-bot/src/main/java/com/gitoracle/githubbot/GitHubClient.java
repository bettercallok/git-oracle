package com.gitoracle.githubbot;

import io.github.cdimascio.dotenv.Dotenv;
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
        Dotenv dotenv = Dotenv.load();
        this.appId = dotenv.get("GITHUB_APP_ID");
        this.installationId = dotenv.get("GITHUB_INSTALLATION_ID");
        this.privateKeyPath = dotenv.get("GITHUB_PRIVATE_KEY_PATH");
        logger.info("Initialized GitHubClient for App ID: {}", this.appId);
    }

    public GitHub getAuthenticatedGitHub() throws Exception {
        // The Kohsuke GitHub API library has a built-in JWTTokenProvider 
        // that automatically handles PKCS#1 vs PKCS#8 decoding!
        JWTTokenProvider jwtProvider = new JWTTokenProvider(appId, new File(privateKeyPath));
        
        // Connect as the GitHub App
        GitHub appClient = new GitHubBuilder().withAuthorizationProvider(jwtProvider).build();
        
        // Exchange for an Installation Token
        String installationToken = appClient.getApp().getInstallationById(Long.parseLong(installationId)).createToken().create().getToken();
        
        // Return a fully authenticated client acting as the installation
        return new GitHubBuilder().withAppInstallationToken(installationToken).build();
    }
}
