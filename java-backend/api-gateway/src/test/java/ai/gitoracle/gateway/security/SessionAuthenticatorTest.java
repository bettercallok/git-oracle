package ai.gitoracle.gateway.security;

import ai.gitoracle.core.security.SessionTokens;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
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

class SessionAuthenticatorTest {

    private static final UUID SESSION = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();
    private static final UUID TENANT = UUID.randomUUID();

    private final String token = SessionTokens.generate().token();
    private JdbcTemplate jdbcTemplate;
    private SessionAuthenticator authenticator;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        authenticator = new SessionAuthenticator(jdbcTemplate);
        ReflectionTestUtils.setField(authenticator, "cacheTtlSeconds", 60L);
    }

    private void stubRow(OffsetDateTime revokedAt, OffsetDateTime expiresAt) {
        Map<String, Object> row = new HashMap<>();
        row.put("id", SESSION);
        row.put("user_id", USER);
        row.put("tenant_id", TENANT);
        row.put("scopes", "jobs:read,jobs:write");
        row.put("revoked_at", revokedAt);
        row.put("expires_at", expiresAt);
        when(jdbcTemplate.queryForMap(anyString(), any(Object[].class))).thenReturn(row);
    }

    private Optional<SessionAuthenticator.SessionPrincipal> auth(String presented) {
        return authenticator.authenticate(presented).block();
    }

    @Test
    void aLiveSessionResolvesToItsUserTenantAndScopes() {
        stubRow(null, OffsetDateTime.now().plusDays(1));

        SessionAuthenticator.SessionPrincipal principal = auth(token).orElseThrow();

        assertThat(principal.sessionId()).isEqualTo(SESSION);
        assertThat(principal.userId()).isEqualTo(USER);
        assertThat(principal.tenantId()).isEqualTo(TENANT);
        assertThat(principal.scopes()).containsExactlyInAnyOrder("jobs:read", "jobs:write");
    }

    @Test
    void theRowIsLookedUpByTheTokensHashNeverTheTokenItself() {
        stubRow(null, OffsetDateTime.now().plusDays(1));

        auth(token);

        verify(jdbcTemplate).queryForMap(anyString(), eq(SessionTokens.hash(token)));
    }

    @Test
    void aRevokedSessionIsRejected() {
        stubRow(OffsetDateTime.now().minusSeconds(1), OffsetDateTime.now().plusDays(1));
        assertThat(auth(token)).isEmpty();
    }

    @Test
    void anExpiredSessionIsRejected() {
        stubRow(null, OffsetDateTime.now().minusSeconds(1));
        assertThat(auth(token)).isEmpty();
    }

    @Test
    void anUnknownTokenIsRejected() {
        when(jdbcTemplate.queryForMap(anyString(), any(Object[].class)))
            .thenThrow(new EmptyResultDataAccessException(1));
        assertThat(auth(token)).isEmpty();
    }

    @Test
    void aMalformedTokenNeverReachesTheDatabase() {
        assertThat(auth("not-a-token")).isEmpty();
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    void aDatabaseFailureRejectsTheRequest() {
        when(jdbcTemplate.queryForMap(anyString(), any(Object[].class)))
            .thenThrow(new DataAccessResourceFailureException("down"));
        assertThat(auth(token)).isEmpty();
    }

    @Test
    void decisionsAreCachedUntilEvicted() {
        stubRow(null, OffsetDateTime.now().plusDays(1));

        auth(token);
        auth(token);
        verify(jdbcTemplate, times(1)).queryForMap(anyString(), any(Object[].class));

        stubRow(OffsetDateTime.now().minusSeconds(1), OffsetDateTime.now().plusDays(1));
        authenticator.evict(token);

        assertThat(auth(token)).isEmpty();
    }
}
