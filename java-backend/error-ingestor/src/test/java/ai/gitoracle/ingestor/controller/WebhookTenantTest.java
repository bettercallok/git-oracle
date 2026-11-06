package ai.gitoracle.ingestor.controller;

import ai.gitoracle.core.model.postgres.Tenant;
import ai.gitoracle.ingestor.security.DeliveryReplayGuard;
import ai.gitoracle.ingestor.service.SemanticDedupService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** A signed delivery's installation decides whose job it is, never a default. */
class WebhookTenantTest {

    private static final UUID ORG = UUID.randomUUID();

    private WebhookController controller;
    private TypedQuery<UUID> query;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        controller = new WebhookController(mock(SemanticDedupService.class), mock(KafkaTemplate.class), new DeliveryReplayGuard());
        EntityManager entityManager = mock(EntityManager.class);
        query = mock(TypedQuery.class);
        when(entityManager.createQuery(anyString(), eq(UUID.class))).thenReturn(query);
        when(query.setParameter(anyString(), org.mockito.ArgumentMatchers.any())).thenReturn(query);
        when(query.getResultList()).thenReturn(List.of());
        ReflectionTestUtils.setField(controller, "entityManager", entityManager);
        ReflectionTestUtils.setField(controller, "defaultInstallationId", "100");
    }

    private static Map<String, Object> from(long installation) {
        return Map.of("installation", Map.of("id", installation));
    }

    @Test
    void aConnectedInstallationGoesToItsOrganisation() {
        when(query.getResultList()).thenReturn(List.of(ORG));
        assertThat(controller.tenantForInstallation(from(55))).contains(ORG);
    }

    @Test
    void theSharedInstallationGoesToTheDefaultOrganisation() {
        assertThat(controller.tenantForInstallation(from(100))).contains(Tenant.DEFAULT_ID);
    }

    @Test
    void anInstallationNobodyConnectedIsDropped() {
        assertThat(controller.tenantForInstallation(from(999))).isEmpty();
    }

    @Test
    void noInstallationMeansTheDefaultOrganisation() {
        assertThat(controller.tenantForInstallation(Map.of())).contains(Tenant.DEFAULT_ID);
    }
}
