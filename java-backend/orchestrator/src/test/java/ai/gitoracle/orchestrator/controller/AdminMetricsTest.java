package ai.gitoracle.orchestrator.controller;

import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.*;

class AdminMetricsTest {

    @Test
    @SuppressWarnings("unchecked")
    void tokensUsedComesFromTheTenantsJobsRatherThanAHardcodedNumber() {
        EntityManager entityManager = mock(EntityManager.class);
        TypedQuery<Long> counts = mock(TypedQuery.class);
        TypedQuery<Long> tokens = mock(TypedQuery.class);
        when(entityManager.createQuery(anyString(), eq(Long.class))).thenReturn(counts);
        when(entityManager.createQuery(contains("SUM(j.tokenBudgetUsed)"), eq(Long.class))).thenReturn(tokens);
        when(counts.setParameter(anyString(), any())).thenReturn(counts);
        when(tokens.setParameter(anyString(), any())).thenReturn(tokens);
        when(counts.getSingleResult()).thenReturn(2L);
        when(tokens.getSingleResult()).thenReturn(1234L);
        UUID tenant = UUID.randomUUID();

        Map<String, Object> body = new AdminController(entityManager).getTenantMetrics(tenant.toString()).getBody();

        assertThat(body).containsEntry("tokensUsed", 1234L);
        verify(tokens).setParameter("tenantId", tenant);
    }
}
