package ai.gitoracle.orchestrator.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * What the system is really running: asks each service whether it's healthy, and
 * each agent what it's configured to use. Nothing here is stored or editable. The
 * values come from the running processes, so the page can't drift from reality.
 *
 * <p>Agents are asked individually because they read their own environment, and a
 * restart can leave them disagreeing about where the LLM is. That's reported
 * rather than hidden behind one answer.
 */
@Service
public class SystemStatusService {

    public record ServiceStatus(String name, String kind, String status, String detail) {}

    private record AgentResult(ServiceStatus status, Map<String, Object> config) {}

    /** Long enough for a service's first health check after a start, which can take seconds. */
    private static final int TIMEOUT_MILLIS = 5_000;

    private final SystemStatusProperties properties;
    private final RestTemplate http;
    private final String internalToken;

    @Autowired
    public SystemStatusService(SystemStatusProperties properties,
                               @Value("${gitoracle.internal-token:}") String internalToken) {
        this(properties, timeoutTemplate(), internalToken);
    }

    SystemStatusService(SystemStatusProperties properties, RestTemplate http, String internalToken) {
        this.properties = properties;
        this.http = http;
        this.internalToken = internalToken;
    }

    private static RestTemplate timeoutTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(TIMEOUT_MILLIS);
        factory.setReadTimeout(TIMEOUT_MILLIS);
        return new RestTemplate(factory);
    }

    public Map<String, Object> status() {
        List<ServiceStatus> services = new ArrayList<>();
        // It's answering this request, so it needs no probe.
        services.add(new ServiceStatus("orchestrator", "java", "up", null));
        List<Map<String, Object>> configs = new ArrayList<>();

        // In parallel, so one service that's down costs one timeout rather than adding up.
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<ServiceStatus>> java = new ArrayList<>();
            properties.getJavaServices().forEach((name, url) ->
                java.add(pool.submit(() -> probe(name, "java", url + "/actuator/health"))));
            List<Future<AgentResult>> agents = new ArrayList<>();
            properties.getAgents().forEach((name, url) ->
                agents.add(pool.submit(() -> new AgentResult(probe(name, "agent", url + "/health"), readConfig(url)))));

            for (Future<ServiceStatus> result : java) services.add(result.get());
            for (Future<AgentResult> result : agents) {
                services.add(result.get().status());
                if (result.get().config() != null) configs.add(result.get().config());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while checking services", e);
        } catch (java.util.concurrent.ExecutionException e) {
            throw new IllegalStateException("Checking services failed", e.getCause());
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("services", services);
        body.put("config", configs.isEmpty() ? null : configs.get(0));
        body.put("configConsistent", configs.stream().distinct().count() <= 1);
        return body;
    }

    private ServiceStatus probe(String name, String kind, String url) {
        try {
            http.getForEntity(url, String.class);
            return new ServiceStatus(name, kind, "up", null);
        } catch (HttpStatusCodeException e) {
            // A 503 means it's running but reports itself unhealthy, such as an
            // agent whose Kafka consumer has died. Anything else is a real failure.
            return e.getStatusCode().isSameCodeAs(HttpStatus.SERVICE_UNAVAILABLE)
                ? new ServiceStatus(name, kind, "degraded", "Running, but reports itself unhealthy")
                : new ServiceStatus(name, kind, "down", "Answered HTTP " + e.getStatusCode().value());
        } catch (RestClientException e) {
            return new ServiceStatus(name, kind, "down", "No answer within 5 seconds");
        }
    }

    private Map<String, Object> readConfig(String baseUrl) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Internal-Token", Objects.toString(internalToken, ""));
        try {
            return http.exchange(baseUrl + "/runtime-config", HttpMethod.GET, new HttpEntity<>(headers),
                new ParameterizedTypeReference<Map<String, Object>>() {}).getBody();
        } catch (RestClientException e) {
            return null;
        }
    }
}
