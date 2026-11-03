package ai.gitoracle.ingestor.controller;

import ai.gitoracle.core.kafka.KafkaTopics;
import ai.gitoracle.ingestor.security.DeliveryReplayGuard;
import ai.gitoracle.ingestor.service.SemanticDedupService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * A PR comment's text becomes the Fixer's instructions and the regenerated
 * patch is pushed onto the PR branch with the App's credentials, so only
 * people with standing on the repository may trigger it — not any GitHub
 * account that can comment on a public repo, and not bots.
 */
class ReviewCommentAuthorizationTest {

    private static final String SECRET = "webhook-secret";
    private static final String JOB = "9e09ccf6-1c2b-4f3e-9a7d-0123456789ab";
    private static final String PR = "https://github.com/acme/app/pull/7";

    private KafkaTemplate<String, Object> kafka;
    private WebhookController controller;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        kafka = mock(KafkaTemplate.class);
        controller = new WebhookController(mock(SemanticDedupService.class), kafka, new DeliveryReplayGuard());
        Field f = WebhookController.class.getDeclaredField("githubWebhookSecret");
        f.setAccessible(true);
        f.set(controller, SECRET);
    }

    private void deliver(String body) throws Exception {
        byte[] raw = body.getBytes(StandardCharsets.UTF_8);
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String sig = "sha256=" + HexFormat.of().formatHex(mac.doFinal(raw));
        controller.githubWebhook(UUID.fromString("00000000-0000-0000-0000-000000000000"),
            "issue_comment", UUID.randomUUID().toString(), sig, raw);
    }

    private static String issueComment(String association, String senderType, boolean isPullRequest) {
        return """
            {"action":"created",
             "comment":{"body":"please change it","author_association":"%s"},
             "issue":{"body":"<!-- gitoracle:job_id:%s -->","html_url":"%s"%s},
             "repository":{"full_name":"acme/app"},
             "sender":{"login":"someone","type":"%s"}}
            """.formatted(association, JOB, PR,
                isPullRequest ? ",\"pull_request\":{\"url\":\"x\"}" : "", senderType);
    }

    @ParameterizedTest
    @ValueSource(strings = {"NONE", "CONTRIBUTOR", "FIRST_TIME_CONTRIBUTOR", "FIRST_TIMER", "MANNEQUIN"})
    void outsidersCannotTriggerARegeneration(String association) throws Exception {
        deliver(issueComment(association, "User", true));
        verify(kafka, never()).send(eq(KafkaTopics.JOB_REVIEW_RECEIVED), any());
    }

    @Test
    void botsCannotTriggerARegenerationEvenAsMembers() throws Exception {
        deliver(issueComment("MEMBER", "Bot", true));
        verify(kafka, never()).send(eq(KafkaTopics.JOB_REVIEW_RECEIVED), any());
    }

    @Test
    void aPlainIssueCarryingACopiedMarkerIsIgnored() throws Exception {
        deliver(issueComment("OWNER", "User", false));
        verify(kafka, never()).send(eq(KafkaTopics.JOB_REVIEW_RECEIVED), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void aCollaboratorsCommentIsForwardedWithThePrItWasMadeOn() throws Exception {
        deliver(issueComment("COLLABORATOR", "User", true));

        ArgumentCaptor<Object> event = ArgumentCaptor.forClass(Object.class);
        verify(kafka).send(eq(KafkaTopics.JOB_REVIEW_RECEIVED), event.capture());
        assertThat((Map<String, Object>) event.getValue())
            .containsEntry("job_id", JOB)
            .containsEntry("pr_url", PR);
    }
}
