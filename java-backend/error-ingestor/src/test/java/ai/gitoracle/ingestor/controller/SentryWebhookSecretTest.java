package ai.gitoracle.ingestor.controller;

import ai.gitoracle.core.model.postgres.Tenant;
import ai.gitoracle.ingestor.security.DeliveryReplayGuard;
import ai.gitoracle.ingestor.service.SemanticDedupService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;

import java.lang.reflect.Field;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Security audit D2: one shared SENTRY_WEBHOOK_SECRET with the tenant taken
 * from the URL let whoever held it file jobs under any tenant. Each tenant's
 * Sentry webhook is now verified with that tenant's own secret; the shared
 * one only still works for the default tenant.
 */
class SentryWebhookSecretTest {

    private static final UUID OTHER = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private WebhookController controller;
    private EntityManager entityManager;

    @BeforeEach
    void setUp() throws Exception {
        controller = new WebhookController(mock(SemanticDedupService.class), mock(KafkaTemplate.class),
            new DeliveryReplayGuard());
        entityManager = mock(EntityManager.class);
        set("entityManager", entityManager);
        set("sentryWebhookSecret", "shared-secret");
    }

    private void set(String name, Object value) throws Exception {
        Field f = WebhookController.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(controller, value);
    }

    private Tenant tenantWithSecret(UUID id, String secret) {
        Tenant t = new Tenant();
        t.setId(id);
        t.setSentryWebhookSecret(secret);
        when(entityManager.find(Tenant.class, id)).thenReturn(t);
        return t;
    }

    @Test
    void aTenantsOwnSecretIsUsed() {
        tenantWithSecret(OTHER, "own-secret");
        assertThat(controller.sentrySecretFor(OTHER)).isEqualTo("own-secret");
    }

    @Test
    void theSharedSecretNoLongerWorksForOtherTenants() {
        tenantWithSecret(OTHER, null);
        assertThat(controller.sentrySecretFor(OTHER)).isEmpty();          // HmacVerifier rejects ""
        assertThat(controller.sentrySecretFor(UUID.randomUUID())).isEmpty();  // unknown tenant too
    }

    @Test
    void theDefaultTenantKeepsTheSharedSecretUntilItHasItsOwn() {
        assertThat(controller.sentrySecretFor(Tenant.DEFAULT_ID)).isEqualTo("shared-secret");
        tenantWithSecret(Tenant.DEFAULT_ID, "rotated");
        assertThat(controller.sentrySecretFor(Tenant.DEFAULT_ID)).isEqualTo("rotated");
    }
}
