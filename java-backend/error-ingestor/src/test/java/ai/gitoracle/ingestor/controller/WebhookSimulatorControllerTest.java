package ai.gitoracle.ingestor.controller;

import ai.gitoracle.ingestor.security.DeliveryReplayGuard;
import ai.gitoracle.ingestor.service.SemanticDedupService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.kafka.core.KafkaTemplate;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class WebhookSimulatorControllerTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final String ADMIN = "platform:admin,jobs:read";

    private SemanticDedupService dedup;
    private WebhookSimulatorController controller;

    @BeforeEach
    void setUp() {
        dedup = mock(SemanticDedupService.class);
        controller = new WebhookSimulatorController(
            new WebhookController(dedup, mock(KafkaTemplate.class), new DeliveryReplayGuard()));
    }

    private static Map<String, Object> failedRun() {
        Map<String, Object> run = new HashMap<>(Map.of("id", 123, "conclusion", "failure"));
        Map<String, Object> payload = new HashMap<>();
        payload.put("action", "completed");
        payload.put("workflow_run", run);
        payload.put("repository", Map.of("full_name", "owner/repo"));
        return payload;
    }

    @Test
    @SuppressWarnings("unchecked")
    void anAdminsFailedRunStartsAJobInTheirTenant() {
        var response = controller.simulate(TENANT, ADMIN, failedRun());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        ArgumentCaptor<Map<String, Object>> ingested = ArgumentCaptor.forClass(Map.class);
        verify(dedup).ingest(eq(TENANT), ingested.capture());
        assertThat(ingested.getValue())
            .containsEntry("repo", "https://github.com/owner/repo")
            .containsEntry("error_id", "run-123");
    }

    @Test
    void withoutTheAdminScopeNothingHappens() {
        assertThat(controller.simulate(TENANT, "jobs:read,jobs:write", failedRun()).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(controller.simulate(TENANT, null, failedRun()).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(controller.simulate(null, ADMIN, failedRun()).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        verifyNoInteractions(dedup);
    }

    private void assertRejected(Map<String, Object> payload) {
        assertThat(controller.simulate(TENANT, ADMIN, payload).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verify(dedup, never()).ingest(any(), any());
    }

    @Test
    void aMissingRunIdIsAClearErrorRatherThanACrash() {
        Map<String, Object> payload = failedRun();
        ((Map<String, Object>) payload.get("workflow_run")).remove("id");
        assertRejected(payload);
    }

    @Test
    void aRunThatDidNotFailIsRejected() {
        Map<String, Object> payload = failedRun();
        ((Map<String, Object>) payload.get("workflow_run")).put("conclusion", "success");
        assertRejected(payload);
    }

    @Test
    void otherEventShapesAreRejected() {
        assertRejected(Map.of("action", "created", "comment", Map.of("body", "rewrite this PR")));
    }

    @Test
    void aMalformedRepositoryIsRejected() {
        Map<String, Object> payload = failedRun();
        payload.put("repository", Map.of("full_name", "not a repo"));
        assertRejected(payload);
    }

    @Test
    void aSignedDeliveryMissingARunIdNoLongerThrows() {
        Map<String, Object> payload = failedRun();
        ((Map<String, Object>) payload.get("workflow_run")).remove("id");

        var response = new WebhookController(dedup, mock(KafkaTemplate.class), new DeliveryReplayGuard())
            .handleWorkflowRun(TENANT, payload);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
