package ai.gitoracle.ingestor.controller;

import ai.gitoracle.ingestor.security.DeliveryReplayGuard;
import ai.gitoracle.ingestor.service.SemanticDedupService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Every GitHub event the controller did not recognise used to fall through to
 * dedupService.ingest, which invented "unknown/repo" and a random error id, so
 * each push, ping or installation delivery became a job that could only fail.
 */
class UnhandledGithubEventTest {

    private static final String SECRET = "a-real-webhook-secret-0123456789";

    private SemanticDedupService dedup;
    private WebhookController controller;

    @BeforeEach
    void setUp() {
        dedup = mock(SemanticDedupService.class);
        controller = new WebhookController(dedup, mock(KafkaTemplate.class), new DeliveryReplayGuard());
        ReflectionTestUtils.setField(controller, "githubWebhookSecret", SECRET);
    }

    private static String sign(byte[] body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body));
    }

    private HttpStatus deliver(String event, String json) throws Exception {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        return (HttpStatus) controller.githubWebhook(event, UUID.randomUUID().toString(), sign(body), body).getStatusCode();
    }

    @ParameterizedTest
    @ValueSource(strings = {"push", "ping", "installation"})
    void anUnhandledEventIsAcceptedAndCreatesNoJob(String event) throws Exception {
        assertThat(deliver(event, "{\"ref\":\"refs/heads/main\",\"repository\":{\"full_name\":\"o/r\"}}"))
            .isEqualTo(HttpStatus.ACCEPTED);
        verify(dedup, never()).ingest(any(), any());
    }

    @Test
    void aFailedWorkflowRunStillStartsOneJob() throws Exception {
        String json = "{\"action\":\"completed\",\"workflow_run\":{\"id\":7,\"conclusion\":\"failure\"},"
            + "\"repository\":{\"full_name\":\"o/r\"}}";
        assertThat(deliver("workflow_run", json)).isEqualTo(HttpStatus.ACCEPTED);
        verify(dedup, times(1)).ingest(any(), any());
    }
}
