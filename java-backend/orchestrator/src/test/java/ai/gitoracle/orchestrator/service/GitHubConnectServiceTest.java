package ai.gitoracle.orchestrator.service;

import ai.gitoracle.core.model.postgres.GithubConnectRequest;
import ai.gitoracle.core.model.postgres.Tenant;
import ai.gitoracle.core.model.postgres.UserAccount;
import ai.gitoracle.core.security.ApiKeys;
import ai.gitoracle.orchestrator.auth.GitHubOAuthClient;
import ai.gitoracle.orchestrator.repository.GithubConnectRequestRepository;
import ai.gitoracle.orchestrator.repository.UserAccountRepository;
import ai.gitoracle.orchestrator.service.GitHubConnectService.Outcome;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class GitHubConnectServiceTest {

    private static final UUID ORG = UUID.randomUUID();
    private static final UUID OWNER = UUID.randomUUID();
    private static final long OWNER_GITHUB = 7;
    private static final String STATE = "the-state";

    private GithubConnectRequestRepository requests;
    private OrganizationService organizations;
    private GitHubInstallations installations;
    private GitHubOAuthClient github;
    private AuditLog audit;
    private GitHubConnectService service;
    private GithubConnectRequest request;
    private Tenant tenant;

    @BeforeEach
    void setUp() {
        requests = mock(GithubConnectRequestRepository.class);
        UserAccountRepository users = mock(UserAccountRepository.class);
        organizations = mock(OrganizationService.class);
        installations = mock(GitHubInstallations.class);
        github = mock(GitHubOAuthClient.class);
        audit = mock(AuditLog.class);
        EntityManager entityManager = mock(EntityManager.class);
        tenant = new Tenant();
        when(entityManager.find(eq(Tenant.class), any())).thenReturn(tenant);
        when(github.isConfigured()).thenReturn(true);
        when(github.installUrl(anyString(), anyString())).thenReturn("https://github.com/apps/x/installations/new");
        UserAccount owner = new UserAccount();
        owner.setId(OWNER);
        owner.setGithubId(OWNER_GITHUB);
        when(users.findById(OWNER)).thenReturn(Optional.of(owner));
        service = new GitHubConnectService(requests, users, organizations, installations, github, audit, entityManager, "my-app");

        request = new GithubConnectRequest();
        request.setTenantId(ORG);
        request.setUserId(OWNER);
        request.setExpiresAt(OffsetDateTime.now().plusMinutes(10));
        when(requests.findByStateHash(ApiKeys.hash(STATE))).thenReturn(Optional.of(request));
    }

    private static GitHubOAuthClient.UserInstallations proof(long userId, Long... installs) {
        return new GitHubOAuthClient.UserInstallations(new GitHubOAuthClient.GitHubUser(userId, "someone"), Set.of(installs));
    }

    @Test
    void onlyAnOwnerCanStart() {
        OrganizationService.Actor admin = new OrganizationService.Actor(UUID.randomUUID(), false);
        doThrow(new OrganizationService.OrganizationException(HttpStatus.FORBIDDEN, "no"))
            .when(organizations).requireOwner(ORG, admin);

        assertThatThrownBy(() -> service.start(ORG, admin)).isInstanceOf(OrganizationService.OrganizationException.class);
        verify(requests, never()).save(any());
    }

    @Test
    void startingStoresOnlyAHashOfTheState() {
        service.start(ORG, new OrganizationService.Actor(OWNER, false));

        ArgumentCaptor<String> state = ArgumentCaptor.forClass(String.class);
        verify(github).installUrl(eq("my-app"), state.capture());
        ArgumentCaptor<GithubConnectRequest> saved = ArgumentCaptor.forClass(GithubConnectRequest.class);
        verify(requests).save(saved.capture());
        assertThat(saved.getValue().getStateHash()).isEqualTo(ApiKeys.hash(state.getValue())).isNotEqualTo(state.getValue());
        assertThat(saved.getValue().getTenantId()).isEqualTo(ORG);
    }

    @Test
    void connectingNeedsTheServerToKnowTheAppSlug() {
        GitHubConnectService noSlug = new GitHubConnectService(requests, null, organizations, installations, github, audit, null, "");

        assertThat(noSlug.isAvailable()).isFalse();
        assertThatThrownBy(() -> noSlug.start(ORG, new OrganizationService.Actor(OWNER, false)))
            .isInstanceOf(OrganizationService.OrganizationException.class);
    }

    @Test
    void anUnknownOrExpiredStateCantRecordAnInstallation() {
        assertThat(service.recordInstallation("forged", "55", "install")).contains(Outcome.FAILED);

        request.setExpiresAt(OffsetDateTime.now().minusSeconds(1));
        assertThat(service.recordInstallation(STATE, "55", "install")).contains(Outcome.FAILED);
    }

    @Test
    void aNonNumericInstallationIsRefused() {
        assertThat(service.recordInstallation(STATE, "55; DROP", "install")).contains(Outcome.FAILED);
        assertThat(request.getInstallationId()).isNull();
    }

    @Test
    void aMemberWhoCanOnlyRequestInstallationIsToldSo() {
        assertThat(service.recordInstallation(STATE, null, "request")).contains(Outcome.REQUESTED);
    }

    @Test
    void theInstallationConnectsOnlyAfterGitHubConfirmsThePersonCanAccessIt() {
        assertThat(service.recordInstallation(STATE, "55", "install")).isEmpty();
        assertThat(tenant.getGithubAppInstallationId()).as("the redirect alone proves nothing").isNull();

        assertThat(service.complete(STATE, proof(OWNER_GITHUB, 55L))).isEqualTo(Outcome.CONNECTED);

        assertThat(tenant.getGithubAppInstallationId()).isEqualTo("55");
        verify(audit).record(ORG, OWNER, "github.connected", "55", null);
    }

    @Test
    void someoneElsesInstallationIdInTheUrlIsRefused() {
        request.setInstallationId(55L);

        assertThat(service.complete(STATE, proof(OWNER_GITHUB, 99L))).isEqualTo(Outcome.NOT_YOURS);

        assertThat(tenant.getGithubAppInstallationId()).isNull();
    }

    @Test
    void itMustBeThePersonWhoStarted() {
        request.setInstallationId(55L);

        assertThat(service.complete(STATE, proof(12345, 55L))).isEqualTo(Outcome.WRONG_USER);

        assertThat(tenant.getGithubAppInstallationId()).isNull();
    }

    @Test
    void anInstallationAnotherOrganisationUsesIsRefused() {
        request.setInstallationId(55L);
        when(installations.isTakenByAnother(ORG, "55")).thenReturn(true);

        assertThat(service.complete(STATE, proof(OWNER_GITHUB, 55L))).isEqualTo(Outcome.TAKEN);

        assertThat(tenant.getGithubAppInstallationId()).isNull();
    }

    @Test
    void someoneWhoStoppedBeingAnOwnerMidwayCantFinish() {
        request.setInstallationId(55L);
        doThrow(new OrganizationService.OrganizationException(HttpStatus.FORBIDDEN, "no"))
            .when(organizations).requireOwner(eq(ORG), any());

        assertThatThrownBy(() -> service.complete(STATE, proof(OWNER_GITHUB, 55L)))
            .isInstanceOf(OrganizationService.OrganizationException.class);
        assertThat(tenant.getGithubAppInstallationId()).isNull();
    }

    @Test
    void aRequestCanOnlyBeUsedOnce() {
        request.setInstallationId(55L);
        service.complete(STATE, proof(OWNER_GITHUB, 55L));

        assertThat(service.isConnecting(STATE)).isFalse();
        assertThat(service.complete(STATE, proof(OWNER_GITHUB, 55L))).isEqualTo(Outcome.FAILED);
    }

    @Test
    void disconnectingIsOwnerOnlyAndAudited() {
        tenant.setGithubAppInstallationId("55");
        OrganizationService.Actor owner = new OrganizationService.Actor(OWNER, false);

        service.disconnect(ORG, owner);

        verify(organizations).requireOwner(ORG, owner);
        assertThat(tenant.getGithubAppInstallationId()).isNull();
        verify(audit).record(ORG, "github.disconnected", "55", null);
    }
}
