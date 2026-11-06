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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class GitHubInstallationsTest {

    private static final UUID ORG = UUID.randomUUID();

    private EntityManager entityManager;
    private GitHubInstallations installations;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        entityManager = mock(EntityManager.class);
        GitHubClientService github = mock(GitHubClientService.class);
        when(github.defaultInstallationId()).thenReturn("100");
        TypedQuery<Long> query = mock(TypedQuery.class);
        when(entityManager.createQuery(anyString(), eq(Long.class))).thenReturn(query);
        when(query.setParameter(anyString(), any())).thenReturn(query);
        when(query.getSingleResult()).thenReturn(0L);
        installations = new GitHubInstallations(entityManager, github);
    }

    private void tenantWith(UUID id, String installation) {
        Tenant tenant = new Tenant();
        tenant.setGithubAppInstallationId(installation);
        when(entityManager.find(Tenant.class, id)).thenReturn(tenant);
    }

    @Test
    void anOrganisationUsesItsOwnInstallation() {
        tenantWith(ORG, "55");
        assertThat(installations.find(ORG)).contains("55");
    }

    @Test
    void anOrganisationWithNoneNeverFallsBackToTheSharedOne() {
        tenantWith(ORG, null);

        assertThat(installations.find(ORG)).isEmpty();
        assertThatThrownBy(() -> installations.require(ORG)).isInstanceOf(GitHubNotConnectedException.class);
    }

    @Test
    void theDefaultOrganisationFallsBackToTheConfiguredOne() {
        tenantWith(Tenant.DEFAULT_ID, null);
        assertThat(installations.find(Tenant.DEFAULT_ID)).contains("100");
    }

    @Test
    void theOldSeededPlaceholderDoesntCountAsAnInstallation() {
        tenantWith(Tenant.DEFAULT_ID, "123456");
        assertThat(installations.find(Tenant.DEFAULT_ID)).contains("100");
    }

    @Test
    void noOrganisationCanClaimTheSharedInstallation() {
        assertThat(installations.isTakenByAnother(ORG, "100")).isTrue();
        assertThat(installations.isTakenByAnother(Tenant.DEFAULT_ID, "100")).isFalse();
    }
}
