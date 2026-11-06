package ai.gitoracle.orchestrator.service;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/** Uses real HTTP servers, because how refused connections and 503s surface is the point. */
class SystemStatusServiceTest {

    private static final String UNREACHABLE = "http://127.0.0.1:1";

    private final List<HttpServer> servers = new ArrayList<>();
    private final List<String> tokensSeen = new CopyOnWriteArrayList<>();

    @AfterEach
    void stopServers() {
        servers.forEach(server -> server.stop(0));
    }

    private String server(int healthStatus, int configStatus, String config) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            boolean isConfig = exchange.getRequestURI().getPath().equals("/runtime-config");
            if (isConfig) tokensSeen.add(exchange.getRequestHeaders().getFirst("X-Internal-Token"));
            byte[] body = (isConfig ? config : "{}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(isConfig ? configStatus : healthStatus, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        servers.add(server);
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private Map<String, Object> statusOf(Map<String, String> java, Map<String, String> agents) {
        SystemStatusProperties properties = new SystemStatusProperties();
        properties.setJavaServices(java);
        properties.setAgents(agents);
        return new SystemStatusService(properties, new RestTemplate(), "the-token").status();
    }

    @SuppressWarnings("unchecked")
    private static SystemStatusService.ServiceStatus find(Map<String, Object> status, String name) {
        return ((List<SystemStatusService.ServiceStatus>) status.get("services")).stream()
            .filter(service -> service.name().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void healthyDegradedAndDownServicesAreToldApart() throws IOException {
        Map<String, Object> status = statusOf(
            Map.of("gateway", server(200, 200, "{}"), "ingestor", UNREACHABLE),
            Map.of("fixer", server(503, 200, "{}")));

        assertThat(find(status, "orchestrator").status()).isEqualTo("up");
        assertThat(find(status, "gateway").status()).isEqualTo("up");
        assertThat(find(status, "ingestor").status()).isEqualTo("down");
        assertThat(find(status, "fixer").status()).isEqualTo("degraded");
    }

    @Test
    void anUnexpectedErrorCountsAsDown() throws IOException {
        Map<String, Object> status = statusOf(Map.of("gateway", server(500, 200, "{}")), Map.of());

        assertThat(find(status, "gateway").status()).isEqualTo("down");
        assertThat(find(status, "gateway").detail()).contains("500");
    }

    @Test
    void theConfigComesFromTheAgentsAndUsesTheInternalToken() throws IOException {
        Map<String, Object> status = statusOf(Map.of(), Map.of("fixer", server(200, 200, "{\"llm\":{\"model\":\"m\"}}")));

        assertThat(status.get("config")).isEqualTo(Map.of("llm", Map.of("model", "m")));
        assertThat(status.get("configConsistent")).isEqualTo(true);
        assertThat(tokensSeen).containsExactly("the-token");
    }

    @Test
    void agentsThatDisagreeAreReported() throws IOException {
        Map<String, Object> status = statusOf(Map.of(), Map.of(
            "fixer", server(200, 200, "{\"llm\":{\"model\":\"new\"}}"),
            "planner", server(200, 200, "{\"llm\":{\"model\":\"old\"}}")));

        assertThat(status.get("configConsistent")).isEqualTo(false);
    }

    @Test
    void noAgentAnsweringMeansUnknownRatherThanADefault() {
        Map<String, Object> status = statusOf(Map.of(), Map.of("fixer", UNREACHABLE));

        assertThat(status.get("config")).isNull();
        assertThat(find(status, "fixer").status()).isEqualTo("down");
    }
}
