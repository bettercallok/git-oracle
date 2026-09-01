package ai.gitoracle.gateway.security;

import ai.gitoracle.core.model.postgres.ApiKey;
import ai.gitoracle.core.security.ApiKeys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * The tenant a request runs as is now decided here and nowhere else, so these
 * tests are the ones that matter: every case where a key should NOT resolve is
 * a case where a caller would otherwise have been handed somebody else's tenant.
 */
class ApiKeyAuthenticatorTest {

    private static final UUID TENANT_A = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private JdbcTemplate jdbcTemplate;
    private ApiKeyAuthenticator authenticator;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        authenticator = new ApiKeyAuthenticator(jdbcTemplate);
        ReflectionTestUtils.setField(authenticator, "cacheTtlSeconds", 60L);
        ReflectionTestUtils.setField(authenticator, "legacyApiKey", "");
        ReflectionTestUtils.setField(authenticator, "legacyApiKeyEnabled", false);
        ReflectionTestUtils.setField(authenticator, "defaultTenantId", "00000000-0000-0000-0000-000000000000");
    }

    private void stubRow(String keyHash, UUID tenantId, String scopes,
                         OffsetDateTime revokedAt, OffsetDateTime expiresAt) {
        Map<String, Object> row = new HashMap<>();
        row.put("key_hash", keyHash);
        row.put("tenant_id", tenantId);
        row.put("scopes", scopes);
        row.put("revoked_at", revokedAt);
        row.put("expires_at", expiresAt);
        when(jdbcTemplate.queryForMap(anyString(), any(Object[].class))).thenReturn(row);
    }

    private Optional<ApiKeyAuthenticator.Principal> auth(String key) {
        return authenticator.authenticate(key).block();
    }

    @Test
    void aValidKeyResolvesToItsOwnTenantAndScopes() {
        ApiKeys.GeneratedKey key = ApiKeys.generate();
        stubRow(key.hash(), TENANT_A, "jobs:read,jobs:write", null, null);

        Optional<ApiKeyAuthenticator.Principal> result = auth(key.presented());

        assertThat(result).isPresent();
        assertThat(result.get().tenantId()).isEqualTo(TENANT_A);
        assertThat(result.get().scopes()).containsExactlyInAnyOrder("jobs:read", "jobs:write");
        assertThat(result.get().legacy()).isFalse();
    }

    @Test
    void aKeyWithTheRightPrefixButWrongSecretIsRejected() {
        // The attack this specifically prevents: the prefix is public (it can be
        // read off a management UI or a log line), so locating the row must not
        // be the same thing as authenticating.
        ApiKeys.GeneratedKey real = ApiKeys.generate();
        stubRow(real.hash(), TENANT_A, "jobs:read", null, null);

        String forged = "gor_" + real.prefix() + "_" + "a".repeat(64);

        assertThat(auth(forged)).isEmpty();
    }

    @Test
    void anUnknownKeyIsRejected() {
        when(jdbcTemplate.queryForMap(anyString(), any(Object[].class)))
            .thenThrow(new EmptyResultDataAccessException(1));

        assertThat(auth(ApiKeys.generate().presented())).isEmpty();
    }

    @Test
    void aRevokedKeyIsRejected() {
        ApiKeys.GeneratedKey key = ApiKeys.generate();
        stubRow(key.hash(), TENANT_A, "jobs:read", OffsetDateTime.now().minusMinutes(1), null);

        assertThat(auth(key.presented())).isEmpty();
    }

    @Test
    void anExpiredKeyIsRejected() {
        ApiKeys.GeneratedKey key = ApiKeys.generate();
        stubRow(key.hash(), TENANT_A, "jobs:read", null, OffsetDateTime.now().minusSeconds(1));

        assertThat(auth(key.presented())).isEmpty();
    }

    @Test
    void aKeyExpiringInTheFutureStillWorks() {
        ApiKeys.GeneratedKey key = ApiKeys.generate();
        stubRow(key.hash(), TENANT_A, "jobs:read", null, OffsetDateTime.now().plusDays(1));

        assertThat(auth(key.presented())).isPresent();
    }

    @Test
    void aDatabaseFailureFailsClosedRatherThanGrantingAccess() {
        // An outage must not become an authentication bypass — nor may it grant
        // a key we were unable to verify.
        when(jdbcTemplate.queryForMap(anyString(), any(Object[].class)))
            .thenThrow(new RuntimeException("connection refused"));

        assertThat(auth(ApiKeys.generate().presented())).isEmpty();
    }

    @Test
    void aMissingOrBlankKeyIsRejectedWithoutTouchingTheDatabase() {
        assertThat(auth(null)).isEmpty();
        assertThat(auth("")).isEmpty();
        assertThat(auth("   ")).isEmpty();

        verify(jdbcTemplate, never()).queryForMap(anyString(), any(Object[].class));
    }

    @Test
    void aMalformedKeyNeverReachesTheDatabaseLookup() {
        // Anything not shaped like a GitOracle key can only be the legacy
        // secret, which is disabled here — so it must be rejected outright and
        // must not cost a query.
        assertThat(auth("not-a-key")).isEmpty();
        assertThat(auth("gor_short_abc")).isEmpty();

        verify(jdbcTemplate, never()).queryForMap(anyString(), any(Object[].class));
    }

    @Test
    void repeatedAuthenticationOfTheSameKeyHitsTheDatabaseOnlyOnce() {
        ApiKeys.GeneratedKey key = ApiKeys.generate();
        stubRow(key.hash(), TENANT_A, "jobs:read", null, null);

        for (int i = 0; i < 5; i++) {
            assertThat(auth(key.presented())).isPresent();
        }

        verify(jdbcTemplate, times(1)).queryForMap(anyString(), any(Object[].class));
    }

    @Test
    void anUnknownKeyIsAlsoCachedSoRepeatedGuessesDoNotHammerTheDatabase() {
        when(jdbcTemplate.queryForMap(anyString(), any(Object[].class)))
            .thenThrow(new EmptyResultDataAccessException(1));

        ApiKeys.GeneratedKey key = ApiKeys.generate();
        for (int i = 0; i < 5; i++) {
            assertThat(auth(key.presented())).isEmpty();
        }

        verify(jdbcTemplate, times(1)).queryForMap(anyString(), any(Object[].class));
    }

    @Test
    void invalidateAllForcesAFreshLookup() {
        ApiKeys.GeneratedKey key = ApiKeys.generate();
        stubRow(key.hash(), TENANT_A, "jobs:read", null, null);

        auth(key.presented());
        authenticator.invalidateAll();
        auth(key.presented());

        verify(jdbcTemplate, times(2)).queryForMap(anyString(), any(Object[].class));
    }

    // ── Legacy shared key ────────────────────────────────────────────────────

    @Test
    void theLegacyKeyResolvesToTheDefaultTenantWhenEnabled() {
        ReflectionTestUtils.setField(authenticator, "legacyApiKey", "legacy-shared-secret");
        ReflectionTestUtils.setField(authenticator, "legacyApiKeyEnabled", true);

        Optional<ApiKeyAuthenticator.Principal> result = auth("legacy-shared-secret");

        assertThat(result).isPresent();
        assertThat(result.get().tenantId())
            .isEqualTo(UUID.fromString("00000000-0000-0000-0000-000000000000"));
        assertThat(result.get().legacy()).isTrue();
        assertThat(result.get().hasScope(ApiKey.Scopes.PLATFORM_ADMIN)).isTrue();
    }

    @Test
    void theLegacyKeyIsRejectedOnceDisabled() {
        // This is the switch an operator flips after minting real keys; if it
        // did not actually close the path, the migration would be theatre.
        ReflectionTestUtils.setField(authenticator, "legacyApiKey", "legacy-shared-secret");
        ReflectionTestUtils.setField(authenticator, "legacyApiKeyEnabled", false);

        assertThat(auth("legacy-shared-secret")).isEmpty();
    }

    @Test
    void anUnconfiguredLegacyKeyDoesNotMatchABlankOrArbitraryCredential() {
        ReflectionTestUtils.setField(authenticator, "legacyApiKey", "");
        ReflectionTestUtils.setField(authenticator, "legacyApiKeyEnabled", true);

        assertThat(auth("anything")).isEmpty();
        assertThat(auth("")).isEmpty();
    }

    @Test
    void aWrongLegacySecretIsRejected() {
        ReflectionTestUtils.setField(authenticator, "legacyApiKey", "legacy-shared-secret");
        ReflectionTestUtils.setField(authenticator, "legacyApiKeyEnabled", true);

        assertThat(auth("legacy-shared-secre")).isEmpty();
        assertThat(auth("legacy-shared-secretX")).isEmpty();
        assertThat(auth("LEGACY-SHARED-SECRET")).isEmpty();
    }
}
