package com.gitoracle.githubbot.security;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The exempt-path check against a REAL Tomcat, because that is where the bypass
 * lived: {@code getRequestURI()} is the raw request line, but Tomcat dispatches
 * on the normalised path. With a raw {@code startsWith} test,
 * {@code GET /health/../pull-request/status} matched the {@code /health}
 * exemption, skipped the token check, and was then routed to the real
 * endpoint. MockMvc does not normalise, so it cannot reproduce this — the
 * request goes over a plain socket because every HTTP client library
 * collapses {@code ..} before sending.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
                properties = {"gitoracle.internal-token=test-internal-token", "server.address=127.0.0.1"})
class InternalAuthPathBypassTest {

    @LocalServerPort
    int port;

    @ParameterizedTest
    @ValueSource(strings = {
        "/health/../pull-request/status?url=x",
        "/actuator/../pull-request/status?url=x",
        "/actuator/..;/pull-request/status?url=x",
        "/health/%2e%2e/pull-request/status?url=x",
        "/health/./../pull-request/status?url=x",
    })
    void dotSegmentsUnderAnExemptPrefixDoNotSkipTheTokenCheck(String rawPath) throws Exception {
        assertThat(statusOf(rawPath)).isEqualTo(401);
    }

    @Test
    void theRealRouteStillRequiresTheToken() throws Exception {
        assertThat(statusOf("/pull-request/status?url=x")).isEqualTo(401);
    }

    @Test
    void theExemptHealthProbeStillWorksWithoutAToken() throws Exception {
        assertThat(statusOf("/actuator/health")).isNotEqualTo(401);
    }

    private int statusOf(String rawPath) throws Exception {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(10_000);
            OutputStream out = socket.getOutputStream();
            out.write(("GET " + rawPath + " HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII));
            out.flush();
            InputStream in = socket.getInputStream();
            String statusLine = new String(in.readNBytes(12), StandardCharsets.US_ASCII); // "HTTP/1.1 401"
            return Integer.parseInt(statusLine.substring(9, 12));
        }
    }
}
