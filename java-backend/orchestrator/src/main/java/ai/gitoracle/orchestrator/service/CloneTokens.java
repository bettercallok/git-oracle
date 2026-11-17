package ai.gitoracle.orchestrator.service;

import ai.gitoracle.core.security.RepoNames;
import org.kohsuke.github.GHAppInstallationToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tokens for cloning a job's repository, so private repositories work end to end.
 *
 * <p>Each is the organisation's GitHub App installation token narrowed to one
 * repository and {@code contents: read}, minted by {@link GitHubClientService}.
 * GitHub keeps them valid for an hour; one is reused until ten minutes before
 * that, so a job's many clones cost one GitHub call. No connected installation,
 * or a repository the installation can't see, means no token: the clone is then
 * tried anonymously, which still works for a public repository.
 */
@Service
public class CloneTokens {

    private static final Logger logger = LoggerFactory.getLogger(CloneTokens.class);
    static final Duration REFRESH_BEFORE_EXPIRY = Duration.ofMinutes(10);

    public record Token(String value, Instant expiresAt) {
        /** Never print the token itself. */
        @Override
        public String toString() {
            return "Token[expiresAt=" + expiresAt + "]";
        }
    }

    @FunctionalInterface
    interface Minter {
        GHAppInstallationToken mint(String installationId, String repoName) throws Exception;
    }

    private final GitHubInstallations installations;
    private final Minter minter;
    private final Clock clock;
    private final Map<String, Token> cache = new ConcurrentHashMap<>();

    @Autowired
    public CloneTokens(GitHubInstallations installations, GitHubClientService github) {
        this(installations, github::getRepositoryReadToken, Clock.systemUTC());
    }

    CloneTokens(GitHubInstallations installations, Minter minter, Clock clock) {
        this.installations = installations;
        this.minter = minter;
        this.clock = clock;
    }

    /** A read token for {@code repoUrl} in {@code tenantId}'s installation, or empty. */
    public Optional<Token> forRepository(UUID tenantId, String repoUrl) {
        if (tenantId == null || repoUrl == null || repoUrl.isBlank()) return Optional.empty();
        Optional<String> installation = installations.find(tenantId);
        if (installation.isEmpty()) return Optional.empty();
        String slug = RepoNames.slug(repoUrl);
        int slash = slug.indexOf('/');
        if (slash <= 0 || slash == slug.length() - 1) return Optional.empty();

        String key = installation.get() + "|" + slug;
        Token cached = cache.get(key);
        Instant now = clock.instant();
        if (cached != null && cached.expiresAt().minus(REFRESH_BEFORE_EXPIRY).isAfter(now)) {
            return Optional.of(cached);
        }
        try {
            GHAppInstallationToken minted = minter.mint(installation.get(), slug.substring(slash + 1));
            Instant expires = minted.getExpiresAt() == null
                ? now.plus(Duration.ofHours(1)) : minted.getExpiresAt().toInstant();
            Token token = new Token(minted.getToken(), expires);
            cache.put(key, token);
            return Optional.of(token);
        } catch (Exception e) {
            // Usually a repository the installation doesn't include; public ones still clone.
            logger.info("No clone token for {} (installation {}): {}", slug, installation.get(), e.getMessage());
            return Optional.empty();
        }
    }

    /** The token's value for {@code repoUrl}, or null: for callers that only pass it on. */
    public String valueFor(UUID tenantId, String repoUrl) {
        return forRepository(tenantId, repoUrl).map(Token::value).orElse(null);
    }
}
