package ai.gitoracle.orchestrator.controller;

import ai.gitoracle.core.model.postgres.ApiKey;
import ai.gitoracle.core.security.ApiKeys;
import ai.gitoracle.orchestrator.dto.Requests;
import ai.gitoracle.orchestrator.repository.ApiKeyRepository;
import ai.gitoracle.orchestrator.security.TenantContextTestSupport;
import ai.gitoracle.orchestrator.service.AuditLog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ApiKeyControllerTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();

    private ApiKeyRepository repository;
    private ApiKeyController controller;

    @BeforeEach
    void setUp() {
        repository = mock(ApiKeyRepository.class);
        when(repository.findByTenantIdOrderByCreatedAtDesc(TENANT)).thenReturn(List.of());
        controller = new ApiKeyController(repository, mock(AuditLog.class));
    }

    private <T> T asUser(Supplier<T> body) {
        return TenantContextTestSupport.runAs(TENANT, Set.of("jobs:read", "jobs:write"), body);
    }

    private ApiKey saved() {
        ArgumentCaptor<ApiKey> key = ArgumentCaptor.forClass(ApiKey.class);
        verify(repository).save(key.capture());
        return key.getValue();
    }

    private static ApiKey row(UUID tenant, OffsetDateTime expiresAt, OffsetDateTime revokedAt) {
        ApiKey key = new ApiKey();
        key.setTenantId(tenant);
        key.setKeyPrefix("abc");
        key.setKeyHash("secret-hash");
        key.setCreatedByUserId(USER);
        key.setExpiresAt(expiresAt);
        key.setRevokedAt(revokedAt);
        return key;
    }

    @Test
    void anApiKeyCannotManageKeys() {
        Requests.CreateApiKey request = new Requests.CreateApiKey("ci", 30, null);

        assertThat(asUser(() -> controller.list(null)).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(asUser(() -> controller.create(null, request)).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(asUser(() -> controller.revoke(null, "abc")).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        verifyNoInteractions(repository);
    }

    @Test
    void aNewKeyBelongsToTheCallerAndIsNeverAdmin() {
        asUser(() -> controller.create(USER, new Requests.CreateApiKey(" ci ", 30, null)));

        ApiKey key = saved();
        assertThat(key.getTenantId()).isEqualTo(TENANT);
        assertThat(key.getCreatedByUserId()).isEqualTo(USER);
        assertThat(key.getName()).isEqualTo("ci");
        assertThat(key.scopeSet()).containsExactlyInAnyOrder("jobs:read", "jobs:write");
    }

    @Test
    @SuppressWarnings("unchecked")
    void theFullKeyIsReturnedOnceAndOnlyItsHashIsStored() {
        ResponseEntity<?> response = asUser(() -> controller.create(USER, new Requests.CreateApiKey("ci", 30, null)));

        String presented = (String) ((Map<String, Object>) response.getBody()).get("apiKey");
        ApiKey key = saved();
        assertThat(ApiKeys.looksLikeApiKey(presented)).isTrue();
        assertThat(key.getKeyHash()).isEqualTo(ApiKeys.hash(presented));
    }

    @Test
    void aKeyAlwaysExpiresAndDefaultsTo90Days() {
        asUser(() -> controller.create(USER, new Requests.CreateApiKey("ci", null, null)));

        assertThat(saved().getExpiresAt()).isBetween(
            OffsetDateTime.now().plusDays(90).minusMinutes(1), OffsetDateTime.now().plusDays(90).plusMinutes(1));
    }

    @Test
    void aTenantAtTheLimitMustRevokeOneFirstAndDeadKeysDoNotCount() {
        OffsetDateTime future = OffsetDateTime.now().plusDays(1);
        OffsetDateTime past = OffsetDateTime.now().minusDays(1);
        List<ApiKey> keys = new ArrayList<>();
        for (int i = 0; i < 24; i++) keys.add(row(TENANT, future, null));
        keys.add(row(TENANT, past, null));
        keys.add(row(TENANT, future, past));
        when(repository.findByTenantIdOrderByCreatedAtDesc(TENANT)).thenReturn(keys);
        Requests.CreateApiKey request = new Requests.CreateApiKey("ci", 30, null);

        assertThat(asUser(() -> controller.create(USER, request)).getStatusCode()).isEqualTo(HttpStatus.OK);

        keys.add(row(TENANT, future, null));
        clearInvocations(repository);

        assertThat(asUser(() -> controller.create(USER, request)).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        verify(repository, never()).save(any());
    }

    @Test
    void aKeyCanBeReadOnly() {
        asUser(() -> controller.create(USER, new Requests.CreateApiKey("reports", 30, "read")));

        assertThat(saved().scopeSet()).containsExactly("jobs:read");
    }

    @Test
    void theListNeverIncludesHashes() {
        when(repository.findByTenantIdOrderByCreatedAtDesc(TENANT)).thenReturn(List.of(row(TENANT, null, null)));

        ResponseEntity<?> response = asUser(() -> controller.list(USER));

        assertThat(response.getBody().toString()).doesNotContain("secret-hash");
    }

    @Test
    void revokingKeepsTheRow() {
        ApiKey key = row(TENANT, null, null);
        when(repository.findByKeyPrefix("abc")).thenReturn(Optional.of(key));

        assertThat(asUser(() -> controller.revoke(USER, "abc")).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        assertThat(key.getRevokedAt()).isNotNull();
        verify(repository, never()).delete(any());
    }

    @Test
    void anotherTenantsKeyLooksLikeItDoesNotExist() {
        ApiKey theirs = row(UUID.randomUUID(), null, null);
        when(repository.findByKeyPrefix("abc")).thenReturn(Optional.of(theirs));

        assertThat(asUser(() -> controller.revoke(USER, "abc")).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(theirs.getRevokedAt()).isNull();
    }

    private <T> T asOrgAdmin(Supplier<T> body) {
        return TenantContextTestSupport.runAs(TENANT, Set.of("jobs:read", "jobs:write", "org:admin"), body);
    }

    @Test
    void aMemberSeesAndRevokesOnlyTheirOwnKeys() {
        ApiKey someoneElses = row(TENANT, null, null);
        someoneElses.setCreatedByUserId(UUID.randomUUID());
        when(repository.findByTenantIdOrderByCreatedAtDesc(TENANT)).thenReturn(List.of(someoneElses));
        when(repository.findByKeyPrefix("abc")).thenReturn(Optional.of(someoneElses));

        assertThat((List<?>) asUser(() -> controller.list(USER)).getBody()).isEmpty();
        assertThat(asUser(() -> controller.revoke(USER, "abc")).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(someoneElses.getRevokedAt()).isNull();
    }

    @Test
    void anOrgAdminSeesAndRevokesEveryKeyInTheOrganisation() {
        ApiKey someoneElses = row(TENANT, null, null);
        someoneElses.setCreatedByUserId(UUID.randomUUID());
        when(repository.findByTenantIdOrderByCreatedAtDesc(TENANT)).thenReturn(List.of(someoneElses));
        when(repository.findByKeyPrefix("abc")).thenReturn(Optional.of(someoneElses));

        assertThat((List<?>) asOrgAdmin(() -> controller.list(USER)).getBody()).hasSize(1);
        assertThat(asOrgAdmin(() -> controller.revoke(USER, "abc")).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(someoneElses.getRevokedAt()).isNotNull();
    }
}
