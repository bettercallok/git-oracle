package ai.gitoracle.orchestrator.service;

import ai.gitoracle.core.model.postgres.Tenant;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class UsageLimitsTest {

    private static final UUID ORG = UUID.randomUUID();

    private EntityManager entityManager;
    private TypedQuery<Long> jobs;
    private TypedQuery<Long> tokens;
    private Tenant tenant;
    private UsageLimits limits;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        entityManager = mock(EntityManager.class);
        jobs = mock(TypedQuery.class);
        tokens = mock(TypedQuery.class);
        tenant = new Tenant();
        when(entityManager.find(eq(Tenant.class), any())).thenReturn(tenant);
        when(entityManager.createQuery(contains("COUNT(j)"), eq(Long.class))).thenReturn(jobs);
        when(entityManager.createQuery(contains("SUM(j.tokenBudgetUsed)"), eq(Long.class))).thenReturn(tokens);
        when(jobs.setParameter(anyString(), any())).thenReturn(jobs);
        when(tokens.setParameter(anyString(), any())).thenReturn(tokens);
        when(jobs.getSingleResult()).thenReturn(0L);
        when(tokens.getSingleResult()).thenReturn(0L);
        limits = new UsageLimits(entityManager, 10, 1000);
    }

    private void used(long jobCount, long tokenCount) {
        when(jobs.getSingleResult()).thenReturn(jobCount);
        when(tokens.getSingleResult()).thenReturn(tokenCount);
    }

    @Test
    void anOrganisationGetsTheDefaultsUntilOneIsSet() {
        assertThat(limits.usage(ORG).jobLimit()).isEqualTo(10);
        assertThat(limits.usage(ORG).tokenLimit()).isEqualTo(1000L);

        tenant.setMonthlyJobLimit(3);
        tenant.setMonthlyTokenLimit(30L);
        assertThat(limits.usage(ORG).jobLimit()).isEqualTo(3);
        assertThat(limits.usage(ORG).tokenLimit()).isEqualTo(30L);
    }

    @Test
    void theOperatorsOwnOrganisationHasNoLimitUnlessSet() {
        used(10_000, 10_000_000);
        assertThat(limits.refusal(Tenant.DEFAULT_ID, true)).isEmpty();

        tenant.setMonthlyJobLimit(1);
        assertThat(limits.refusal(Tenant.DEFAULT_ID, true)).isPresent();
    }

    @Test
    void aZeroDefaultMeansNoLimit() {
        used(10_000, 10_000_000);
        assertThat(new UsageLimits(entityManager, 0, 0).refusal(ORG, true)).isEmpty();
    }

    @Test
    void theLastJobIsAllowedAndTheNextIsNot() {
        used(9, 0);
        assertThat(limits.refusal(ORG, true)).isEmpty();

        used(10, 0);
        assertThat(limits.refusal(ORG, true)).get().asString().contains("10 jobs");
        assertThatThrownBy(() -> limits.requireRoomForNewJob(ORG)).isInstanceOf(UsageLimitExceededException.class);
    }

    @Test
    void aJobAlreadyCountedIsntCountedTwice() {
        used(10, 0);
        assertThat(limits.refusal(ORG, false)).isEmpty();

        used(11, 0);
        assertThat(limits.refusal(ORG, false)).isPresent();
    }

    @Test
    void reachingTheTokenLimitStopsNewWork() {
        used(0, 1000);
        assertThat(limits.refusal(ORG, false)).get().asString().contains("tokens");
    }

    @Test
    void itOnlyCountsThisMonthForThisOrganisation() {
        limits.usage(ORG);

        verify(jobs).setParameter("tenantId", ORG);
        verify(jobs).setParameter(eq("since"), org.mockito.ArgumentMatchers.argThat((Object since) ->
            since instanceof java.time.OffsetDateTime time && time.getDayOfMonth() == 1 && time.getHour() == 0));
    }
}
