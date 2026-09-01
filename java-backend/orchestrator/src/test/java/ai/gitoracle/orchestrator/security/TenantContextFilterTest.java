package ai.gitoracle.orchestrator.security;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * H3: {@code AdminController} used to have no authorization of its own — it
 * was only ever as protected as "the gateway happened to check the scope
 * before proxying." These tests pin the defense-in-depth gate this filter now
 * adds directly at the orchestrator, independent of the gateway: any
 * {@code /api/v1/admin/**} request without {@code platform:admin} in its
 * (server-trusted) {@code X-Scopes} header is rejected here, regardless of how
 * it arrived.
 *
 * Also covers the ordinary tenant-context behavior this filter has carried
 * since H1/H2/H8/H10: resolving {@code X-Tenant-ID}, defaulting when absent or
 * malformed, and always clearing {@link TenantContext} afterward so a pooled
 * thread can never leak one request's tenant into the next.
 */
class TenantContextFilterTest {

    private TenantContextFilter filter;
    private FilterChain chain;

    @BeforeEach
    void setUp() {
        filter = new TenantContextFilter();
        ReflectionTestUtils.setField(filter, "defaultTenantId", "00000000-0000-0000-0000-000000000000");
        chain = mock(FilterChain.class);
    }

    @AfterEach
    void tearDown() {
        // A failure inside a test must not leak tenant context into whichever
        // test the JUnit engine happens to run next on this thread.
        TenantContext.clear();
    }

    // ── Admin path gate (H3) ────────────────────────────────────────────────

    @Test
    void adminPathIsRejectedWithNoScopesHeaderAtAll() throws Exception {
        // The default posture for every internal caller that is not the
        // gateway — no X-Scopes means no scopes, not admin scopes.
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/admin/tenants");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(403);
        verifyNoInteractions(chain);
    }

    @Test
    void adminPathIsRejectedWithNonAdminScopes() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/admin/tenants/x/metrics");
        request.addHeader("X-Scopes", "jobs:read,jobs:write");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(403);
        verifyNoInteractions(chain);
    }

    @Test
    void adminPathIsAllowedWithThePlatformAdminScope() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/admin/tenants");
        request.addHeader("X-Scopes", "jobs:read,platform:admin");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
    }

    @Test
    void adminSubPathsAreGatedTheSameAsTheAdminRootPath() throws Exception {
        // /api/v1/admin/api-keys, prompts/activate, eval/results — every
        // present and future endpoint under the prefix, not just /tenants.
        for (String path : new String[] {
            "/api/v1/admin/api-keys",
            "/api/v1/admin/prompts/fixer/activate",
            "/api/v1/admin/eval/results"
        }) {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(request, response, mock(FilterChain.class));

            assertThat(response.getStatus())
                .as("path %s must require platform:admin", path)
                .isEqualTo(403);
        }
    }

    @Test
    void aPathThatMerelyStartsWithAdminAsATextPrefixIsNotTreatedAsAdmin() throws Exception {
        // /api/v1/adminXYZ is a different route than /api/v1/admin/XYZ and
        // must not be swept into the admin gate by a naive string prefix
        // check — the same class of bug the gateway's own exempt-path list
        // had before it was tightened to a real path-segment match.
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/adminXYZ");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
    }

    @Test
    void nonAdminPathsAreUnaffectedByMissingScopes() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/jobs");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
    }

    // ── Tenant resolution ────────────────────────────────────────────────────

    @Test
    void resolvesTenantFromTheHeaderAndClearsItAfterward() throws Exception {
        UUID tenant = UUID.randomUUID();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/jobs");
        request.addHeader("X-Tenant-ID", tenant.toString());

        FilterChain capturing = (req, res) -> assertThat(TenantContext.tenantId()).isEqualTo(tenant);
        filter.doFilter(request, new MockHttpServletResponse(), capturing);

        assertThat(TenantContext.tenantId()).isNull();
    }

    @Test
    void fallsBackToTheDefaultTenantWhenHeaderIsAbsent() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/jobs");

        FilterChain capturing = (req, res) ->
            assertThat(TenantContext.tenantId())
                .isEqualTo(UUID.fromString("00000000-0000-0000-0000-000000000000"));
        filter.doFilter(request, new MockHttpServletResponse(), capturing);
    }

    @Test
    void fallsBackToTheDefaultTenantWhenHeaderIsMalformed() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/jobs");
        request.addHeader("X-Tenant-ID", "not-a-uuid");

        FilterChain capturing = (req, res) ->
            assertThat(TenantContext.tenantId())
                .isEqualTo(UUID.fromString("00000000-0000-0000-0000-000000000000"));
        filter.doFilter(request, new MockHttpServletResponse(), capturing);
    }

    @Test
    void clearsContextEvenWhenTheDownstreamChainThrows() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/jobs");
        FilterChain throwing = (req, res) -> { throw new RuntimeException("downstream failure"); };

        try {
            filter.doFilter(request, new MockHttpServletResponse(), throwing);
        } catch (Exception ignored) {
            // expected — the point of this test is what happens to TenantContext
        }

        assertThat(TenantContext.tenantId()).isNull();
    }

    @Test
    void healthAndActuatorPathsBypassBothTenantAndScopeChecks() throws Exception {
        for (String path : new String[] {"/health", "/actuator/health", "/api/v1/health"}) {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(request, response, mock(FilterChain.class));

            assertThat(response.getStatus()).as("path %s should bypass entirely", path).isEqualTo(200);
        }
    }
}
