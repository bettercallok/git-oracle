package ai.gitoracle.orchestrator.service;

import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;

import java.net.URI;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/** Every internal call has a timeout now; the slow ones get a longer one. */
class InternalHttpTest {

    private static Duration timeoutFor(String url) {
        return InternalHttp.readTimeoutFor(URI.create(url));
    }

    @Test
    void eachTargetGetsItsOwnReadTimeout() {
        assertThat(timeoutFor("http://localhost:8084/test")).isEqualTo(Duration.ofMinutes(10));
        assertThat(timeoutFor("http://localhost:8085/pull-request")).isEqualTo(Duration.ofMinutes(3));
        assertThat(timeoutFor("http://localhost:9003/review")).isEqualTo(Duration.ofMinutes(3));
        assertThat(timeoutFor("http://localhost:9006/validate/patch")).isEqualTo(Duration.ofSeconds(60));
        assertThat(timeoutFor("http://localhost:8085/pull-request/status?prUrl=x")).isEqualTo(Duration.ofSeconds(60));
    }

    @Test
    void aReadTimeoutIsRecognisedThroughSpringsWrapper() {
        assertThat(InternalHttp.isTimeout(new ResourceAccessException("I/O error",
            new java.net.SocketTimeoutException("Read timed out")))).isTrue();
        assertThat(InternalHttp.isTimeout(new ResourceAccessException("Connection refused",
            new java.net.ConnectException("refused")))).isFalse();
    }
}
