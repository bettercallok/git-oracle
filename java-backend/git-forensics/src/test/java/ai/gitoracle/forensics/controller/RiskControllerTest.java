package ai.gitoracle.forensics.controller;

import org.junit.jupiter.api.Test;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Security audit D3: /api/v1/risk returned the whole graph — every analysed
 * repository's hotspots and developers — to any tenant. It now asks the
 * orchestrator for the caller's allow-list and shows only those repositories.
 */
class RiskControllerTest {

    private final Neo4jClient neo4j = mock(Neo4jClient.class);
    private final RestTemplate rest = mock(RestTemplate.class);
    private final RiskController controller = new RiskController(neo4j, rest);

    @SuppressWarnings("unchecked")
    private void orchestratorReturns(Object repos) {
        when(rest.exchange(any(String.class), eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class)))
            .thenReturn(ResponseEntity.ok(Map.of("repos", repos)));
    }

    @Test
    void aTenantWithNoAllowedReposSeesNothingAndTheGraphIsNotQueried() {
        orchestratorReturns(List.of());

        ResponseEntity<Map<String, Object>> response = controller.getRiskHeatmap("tenant-a");

        assertThat(response.getBody()).containsEntry("files", List.of()).containsEntry("developers", List.of());
        verifyNoInteractions(neo4j);
    }

    @Test
    @SuppressWarnings("unchecked")
    void theAllowListIsAskedForWithTheCallersTenantAndLowerCased() {
        orchestratorReturns(List.of("Owner/Repo"));

        assertThat(controller.allowedRepos("tenant-a")).containsExactly("owner/repo");

        org.mockito.ArgumentCaptor<HttpEntity<?>> request = org.mockito.ArgumentCaptor.forClass(HttpEntity.class);
        verify(rest).exchange(any(String.class), eq(HttpMethod.GET), request.capture(), eq(Map.class));
        assertThat(request.getValue().getHeaders().getFirst("X-Tenant-ID")).isEqualTo("tenant-a");
    }

    @Test
    @SuppressWarnings("unchecked")
    void anUnreachableOrchestratorIsA503NotTheWholeGraph() {
        when(rest.exchange(any(String.class), eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class)))
            .thenThrow(new ResourceAccessException("connection refused"));

        assertThat(controller.getRiskHeatmap("tenant-a").getStatusCode().value()).isEqualTo(503);
        verifyNoInteractions(neo4j);
    }
}
