package ai.gitoracle.orchestrator.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Reads traces from the self-hosted Langfuse's public API so the dashboard can
 * show them itself.
 *
 * <p>Why the orchestrator and not the browser: Langfuse cannot be framed (it
 * sends {@code frame-ancestors 'none'}), and its API authenticates with the
 * project's <em>secret</em> key, which must not reach a browser. Langfuse also
 * knows nothing about GitOracle tenants. Going through here keeps the key
 * server-side and lets the caller's tenant decide which job traces it may read
 * (see {@code TraceController}).
 *
 * <p>Configured from the same variables the Python agents already write
 * traces with: {@code LANGFUSE_PUBLIC_KEY}, {@code LANGFUSE_SECRET_KEY}, and
 * {@code LANGFUSE_HOST} (a full URL) or else {@code LANGFUSE_PORT} on localhost
 * — the same resolution {@code ai_core/shared/structured_output.py} uses.
 */
@Service
public class LangfuseClient {

    private static final Logger logger = LoggerFactory.getLogger(LangfuseClient.class);

    /** Langfuse is down or misconfigured — distinct from "no trace for this job". */
    public static class LangfuseUnavailableException extends RuntimeException {
        public LangfuseUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String baseUrl;
    private final String publicKey;
    private final String secretKey;

    @Autowired
    public LangfuseClient(@Value("${LANGFUSE_HOST:}") String host,
                          @Value("${LANGFUSE_PORT:3002}") String port,
                          @Value("${LANGFUSE_PUBLIC_KEY:}") String publicKey,
                          @Value("${LANGFUSE_SECRET_KEY:}") String secretKey) {
        this(defaultRestTemplate(), host, port, publicKey, secretKey);
    }

    LangfuseClient(RestTemplate restTemplate, String host, String port, String publicKey, String secretKey) {
        this.restTemplate = restTemplate;
        this.baseUrl = resolveBaseUrl(host, port);
        this.publicKey = publicKey == null ? "" : publicKey.trim();
        this.secretKey = secretKey == null ? "" : secretKey.trim();
    }

    static String resolveBaseUrl(String host, String port) {
        String h = host == null ? "" : host.trim();
        if (h.startsWith("http://") || h.startsWith("https://")) {
            return h.replaceAll("/+$", "");
        }
        String p = port == null || port.isBlank() ? "3002" : port.trim();
        return "http://localhost:" + p;
    }

    private static RestTemplate defaultRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        // A trace view must not hang a request thread on a Langfuse that is down.
        factory.setConnectTimeout(2_000);
        factory.setReadTimeout(10_000);
        return new RestTemplate(factory);
    }

    /** False when no keys are set (CI, or a deployment with tracing off). Placeholders don't count. */
    public boolean isConfigured() {
        return isRealKey(publicKey, "pk-lf-") && isRealKey(secretKey, "sk-lf-");
    }

    private static boolean isRealKey(String key, String prefix) {
        return !key.isBlank() && !key.equals(prefix + "...") && !key.contains("your_");
    }

    /** The full trace (with every observation), or empty when Langfuse has none with this id. */
    public Optional<JsonNode> getTrace(String traceId) {
        String url = UriComponentsBuilder.fromHttpUrl(baseUrl)
            .pathSegment("api", "public", "traces", traceId)
            .toUriString();
        try {
            return Optional.of(get(url));
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode() == HttpStatus.NOT_FOUND) return Optional.empty();
            throw unavailable(e);
        } catch (RestClientException e) {
            throw unavailable(e);
        }
    }

    /**
     * Summaries of the most recent traces, keyed by trace id. Langfuse's list
     * endpoint cannot filter by a set of ids, so the caller intersects this with
     * the jobs it is allowed to see.
     */
    public Map<String, JsonNode> recentTraces(int limit) {
        String url = UriComponentsBuilder.fromHttpUrl(baseUrl)
            .pathSegment("api", "public", "traces")
            .queryParam("page", 1)
            .queryParam("limit", Math.max(1, Math.min(limit, 100)))
            .queryParam("orderBy", "timestamp.desc")
            .toUriString();
        try {
            Map<String, JsonNode> byId = new LinkedHashMap<>();
            for (JsonNode trace : get(url).path("data")) {
                if (trace.hasNonNull("id")) byId.put(trace.get("id").asText(), trace);
            }
            return byId;
        } catch (RestClientException e) {
            throw unavailable(e);
        }
    }

    private JsonNode get(String url) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, "Basic " + Base64.getEncoder()
            .encodeToString((publicKey + ":" + secretKey).getBytes(StandardCharsets.UTF_8)));
        String body = restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), String.class).getBody();
        try {
            return body == null ? objectMapper.createObjectNode() : objectMapper.readTree(body);
        } catch (Exception e) {
            throw new RestClientException("Langfuse returned a body that is not JSON", e);
        }
    }

    private LangfuseUnavailableException unavailable(Exception e) {
        String reason = e instanceof HttpClientErrorException h && h.getStatusCode().value() == 401
            ? "Langfuse rejected the configured LANGFUSE_PUBLIC_KEY / LANGFUSE_SECRET_KEY."
            : "Langfuse at " + baseUrl + " is not reachable (" + e.getClass().getSimpleName() + ").";
        logger.warn("{} {}", reason, e.getMessage());
        return new LangfuseUnavailableException(reason, e);
    }
}
