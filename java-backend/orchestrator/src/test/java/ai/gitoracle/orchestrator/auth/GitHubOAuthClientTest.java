package ai.gitoracle.orchestrator.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class GitHubOAuthClientTest {

    private static final String CALLBACK = "http://localhost:8080/api/v1/auth/github/callback";

    private MockRestServiceServer server;
    private GitHubOAuthClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new GitHubOAuthClient(builder.build(), "cid", "secret", "https://gh.test", "https://api.gh.test");
    }

    private void tokenReply(String json) {
        server.expect(requestTo("https://gh.test/login/oauth/access_token"))
            .andExpect(jsonPath("$.code").value("the-code"))
            .andExpect(jsonPath("$.client_secret").value("secret"))
            .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));
    }

    @Test
    void exchangesTheCodeAndAsksWhoTheUserIs() {
        tokenReply("{\"access_token\":\"tok\"}");
        server.expect(requestTo("https://api.gh.test/user"))
            .andExpect(header("Authorization", "Bearer tok"))
            .andRespond(withSuccess("{\"id\":42,\"login\":\"alice\"}", MediaType.APPLICATION_JSON));

        GitHubOAuthClient.GitHubUser user = client.exchangeCode("the-code", CALLBACK);

        assertThat(user.id()).isEqualTo(42);
        assertThat(user.login()).isEqualTo("alice");
        server.verify();
    }

    @Test
    void aRefusedCodeFailsEvenThoughGitHubAnswers200() {
        tokenReply("{\"error\":\"bad_verification_code\"}");

        assertThatThrownBy(() -> client.exchangeCode("the-code", CALLBACK))
            .isInstanceOf(GitHubOAuthClient.GitHubLoginException.class)
            .hasMessageContaining("bad_verification_code");
    }

    @Test
    void aUserReplyWithNoIdFails() {
        tokenReply("{\"access_token\":\"tok\"}");
        server.expect(requestTo("https://api.gh.test/user"))
            .andRespond(withSuccess("{\"login\":\"alice\"}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.exchangeCode("the-code", CALLBACK))
            .isInstanceOf(GitHubOAuthClient.GitHubLoginException.class);
    }

    @Test
    void anHttpErrorFromGitHubSurfacesAsARestClientException() {
        server.expect(requestTo("https://gh.test/login/oauth/access_token"))
            .andRespond(withStatus(HttpStatus.BAD_GATEWAY));

        assertThatThrownBy(() -> client.exchangeCode("the-code", CALLBACK))
            .isInstanceOf(RestClientException.class);
    }

    @Test
    void theAuthorizeUrlCarriesStateAndCallbackAndRefusesNewSignups() {
        String url = client.authorizeUrl("st", CALLBACK);

        assertThat(url).startsWith("https://gh.test/login/oauth/authorize?client_id=cid")
            .contains("state=st").contains("allow_signup=false")
            .contains("redirect_uri=http://localhost:8080/api/v1/auth/github/callback");
    }

    @Test
    void placeholdersDoNotCountAsConfigured() {
        RestClient http = RestClient.create();
        assertThat(client.isConfigured()).isTrue();
        assertThat(new GitHubOAuthClient(http, "", "s", "w", "a").isConfigured()).isFalse();
        assertThat(new GitHubOAuthClient(http, "your_client_id", "s", "w", "a").isConfigured()).isFalse();
        assertThat(new GitHubOAuthClient(http, "c", "your_secret", "w", "a").isConfigured()).isFalse();
    }
}
