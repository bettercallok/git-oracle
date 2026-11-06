package ai.gitoracle.orchestrator.auth;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Map;
import java.util.Optional;

/**
 * GitHub's web sign-in flow, using the GitHub App's client id and secret.
 *
 * <p>The user's GitHub token is used once, to ask who they are, and then dropped:
 * nothing here needs it again, so nothing stores it.
 */
@Component
public class GitHubOAuthClient {

    public record GitHubUser(long id, String login) {}

    public static class GitHubLoginException extends RuntimeException {
        public GitHubLoginException(String message) {
            super(message);
        }
    }

    private final RestClient http;
    private final String clientId;
    private final String clientSecret;
    private final String webUrl;
    private final String apiUrl;

    @Autowired
    public GitHubOAuthClient(@Value("${gitoracle.auth.github-client-id:}") String clientId,
                             @Value("${gitoracle.auth.github-client-secret:}") String clientSecret,
                             @Value("${gitoracle.auth.github-web-url:https://github.com}") String webUrl,
                             @Value("${gitoracle.auth.github-api-url:https://api.github.com}") String apiUrl) {
        this(RestClient.builder().requestFactory(timeouts()).build(), clientId, clientSecret, webUrl, apiUrl);
    }

    GitHubOAuthClient(RestClient http, String clientId, String clientSecret, String webUrl, String apiUrl) {
        this.http = http;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.webUrl = webUrl;
        this.apiUrl = apiUrl;
    }

    private static SimpleClientHttpRequestFactory timeouts() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5_000);
        factory.setReadTimeout(10_000);
        return factory;
    }

    /** Blank values and the {@code your_*} placeholders from .env.example don't count. */
    public boolean isConfigured() {
        return usable(clientId) && usable(clientSecret);
    }

    private static boolean usable(String value) {
        return value != null && !value.isBlank() && !value.startsWith("your_");
    }

    public String authorizeUrl(String state, String redirectUri) {
        return UriComponentsBuilder.fromHttpUrl(webUrl + "/login/oauth/authorize")
            .queryParam("client_id", clientId)
            .queryParam("redirect_uri", redirectUri)
            .queryParam("state", state)
            .queryParam("allow_signup", "false")
            .encode()
            .toUriString();
    }

    /** Finds an account by login, to tie an invitation to its permanent id. Public data: no token needed. */
    public Optional<GitHubUser> lookupUser(String login) {
        try {
            JsonNode user = http.get()
                .uri(apiUrl + "/users/{login}", login)
                .header("Accept", "application/vnd.github+json")
                .retrieve()
                .body(JsonNode.class);
            if (user == null || !user.path("id").canConvertToLong() || user.path("login").asText("").isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(new GitHubUser(user.path("id").asLong(), user.path("login").asText()));
        } catch (HttpClientErrorException.NotFound e) {
            return Optional.empty();
        }
    }

    /** @throws GitHubLoginException if GitHub refuses the code or answers without a user */
    public GitHubUser exchangeCode(String code, String redirectUri) {
        // GitHub reports a bad code with 200 and an "error" field, not a 4xx.
        JsonNode token = http.post()
            .uri(webUrl + "/login/oauth/access_token")
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.APPLICATION_JSON)
            .body(Map.of(
                "client_id", clientId,
                "client_secret", clientSecret,
                "code", code,
                "redirect_uri", redirectUri))
            .retrieve()
            .body(JsonNode.class);
        String accessToken = token == null ? "" : token.path("access_token").asText("");
        if (accessToken.isEmpty()) {
            throw new GitHubLoginException("GitHub did not issue a token: "
                + (token == null ? "empty reply" : token.path("error").asText("no error given")));
        }

        JsonNode user = http.get()
            .uri(apiUrl + "/user")
            .header("Authorization", "Bearer " + accessToken)
            .header("Accept", "application/vnd.github+json")
            .retrieve()
            .body(JsonNode.class);
        if (user == null || !user.path("id").canConvertToLong() || user.path("id").asLong() <= 0
                || user.path("login").asText("").isEmpty()) {
            throw new GitHubLoginException("GitHub's /user reply had no id or login");
        }
        return new GitHubUser(user.path("id").asLong(), user.path("login").asText());
    }
}
