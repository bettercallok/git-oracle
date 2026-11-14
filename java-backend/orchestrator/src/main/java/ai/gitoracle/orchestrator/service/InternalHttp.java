package ai.gitoracle.orchestrator.service;

import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.time.Duration;

/**
 * RestTemplates for the orchestrator's synchronous calls to other GitOracle
 * services, with timeouts.
 *
 * <p>These were plain {@code new RestTemplate()}s, which wait forever. The
 * {@code /test} call runs on a Kafka listener thread, so one hung test run held
 * that thread, stopped every later job behind it and, past
 * {@code max.poll.interval.ms}, triggered a rebalance that redelivered the event.
 *
 * <p>One template serves every call (so a test's MockRestServiceServer sees them
 * all); the read timeout is chosen per request from the target service.
 */
public final class InternalHttp {

    private InternalHttp() {}

    public static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    /** Test runner worst case: two clone attempts of 180s, 180s of tests, container start-up. */
    public static final Duration TEST_RUNNER_TIMEOUT = Duration.ofMinutes(10);
    public static final Duration GITHUB_BOT_TIMEOUT = Duration.ofMinutes(3);
    public static final Duration REVIEWER_TIMEOUT = Duration.ofMinutes(3);
    /** Guardrails, status checks and anything else not listed. */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60);

    /** For the pipeline: read timeout by target service. */
    public static RestTemplate pipeline() {
        return new RestTemplate(byTarget());
    }

    /** For short calls only (status lookups, Q&A proxies). */
    public static RestTemplate shortCalls() {
        return new RestTemplate(factory(DEFAULT_TIMEOUT));
    }

    static Duration readTimeoutFor(URI uri) {
        String path = uri.getPath() == null ? "" : uri.getPath();
        return switch (uri.getPort()) {
            case 8084 -> path.startsWith("/test") ? TEST_RUNNER_TIMEOUT : DEFAULT_TIMEOUT;
            case 8085 -> path.startsWith("/pull-request") && !path.startsWith("/pull-request/status")
                ? GITHUB_BOT_TIMEOUT : DEFAULT_TIMEOUT;
            case 9003 -> REVIEWER_TIMEOUT;
            default -> DEFAULT_TIMEOUT;
        };
    }

    private static ClientHttpRequestFactory byTarget() {
        return (uri, method) -> factory(readTimeoutFor(uri)).createRequest(uri, method);
    }

    private static SimpleClientHttpRequestFactory factory(Duration readTimeout) {
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(CONNECT_TIMEOUT);
        f.setReadTimeout(readTimeout);
        return f;
    }

    /** True when {@code e} is, or was caused by, a read or connect timeout. */
    public static boolean isTimeout(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof java.net.SocketTimeoutException || t instanceof java.net.http.HttpTimeoutException) {
                return true;
            }
        }
        return false;
    }
}
