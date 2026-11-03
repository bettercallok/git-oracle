package ai.gitoracle.orchestrator.service;

import ai.gitoracle.core.model.postgres.AllowedRepository;
import ai.gitoracle.core.model.postgres.Tenant;
import ai.gitoracle.orchestrator.repository.AllowedRepositoryRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Security audit D1: a tenant may only use repositories on its own allow-list. */
class RepoAccessServiceTest {

    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private final AllowedRepositoryRepository rows = mock(AllowedRepositoryRepository.class);

    @Test
    void aRepoIsAllowedInAnyFormOnceItsSlugIsOnTheList() {
        when(rows.existsByTenantIdAndRepo(TENANT, "owner/repo")).thenReturn(true);
        RepoAccessService access = new RepoAccessService(rows, "");

        assertThat(access.isAllowed(TENANT, "https://github.com/Owner/Repo.git")).isTrue();
        assertThat(access.isAllowed(TENANT, "owner/other")).isFalse();
        assertThat(access.isAllowed(UUID.randomUUID(), "owner/repo")).isFalse();
        assertThat(access.isAllowed(TENANT, "not a repo")).isFalse();
        assertThat(access.isAllowed(null, "owner/repo")).isFalse();
    }

    @Test
    void requireAllowedNamesTheRepoAndHowToAllowIt() {
        RepoAccessService access = new RepoAccessService(rows, "");

        assertThatThrownBy(() -> access.requireAllowed(TENANT, "https://github.com/someone/private"))
            .isInstanceOf(RepoNotAllowedException.class)
            .hasMessageContaining("someone/private")
            .hasMessageContaining("POST /api/v1/admin/repos");
    }

    @Test
    void addingStoresTheSlugOnceAndRejectsNonRepos() {
        RepoAccessService access = new RepoAccessService(rows, "");

        assertThat(access.add(TENANT, "https://github.com/Owner/Repo")).isEqualTo("owner/repo");
        ArgumentCaptor<AllowedRepository> saved = ArgumentCaptor.forClass(AllowedRepository.class);
        verify(rows).save(saved.capture());
        assertThat(saved.getValue().getRepo()).isEqualTo("owner/repo");
        assertThat(saved.getValue().getTenantId()).isEqualTo(TENANT);

        when(rows.existsByTenantIdAndRepo(TENANT, "owner/repo")).thenReturn(true);
        access.add(TENANT, "owner/repo");
        verify(rows, times(1)).save(any());                       // idempotent

        assertThatThrownBy(() -> access.add(TENANT, "../../etc")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theEnvSeedAddsToTheDefaultTenantAndSkipsBadEntries() {
        RepoAccessService access = new RepoAccessService(rows, "bettercallok/chillcall, not-a-repo ,https://github.com/o/r");

        access.seedDefaultTenant();

        ArgumentCaptor<AllowedRepository> saved = ArgumentCaptor.forClass(AllowedRepository.class);
        verify(rows, times(2)).save(saved.capture());
        assertThat(saved.getAllValues()).extracting(AllowedRepository::getRepo)
            .containsExactly("bettercallok/chillcall", "o/r");
        assertThat(saved.getAllValues()).allMatch(r -> r.getTenantId().equals(Tenant.DEFAULT_ID));
    }
}
