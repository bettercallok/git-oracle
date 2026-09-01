package com.gitoracle.githubbot.security;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Every one of GitOracle's 13 services used to bind 0.0.0.0 with no auth of
 * its own — this filter, present on every Java service except the WebFlux
 * api-gateway, closes that by requiring a shared-secret X-Internal-Token on
 * every request. These tests pin the fail-closed behavior (an unconfigured
 * token must refuse everything) and the actual accept/reject decision,
 * without needing a Spring context — a plain OncePerRequestFilter can be
 * exercised directly against mock Servlet request/response objects.
 */
class InternalAuthFilterTest {

    private InternalAuthFilter filter;
    private FilterChain chain;

    @BeforeEach
    void setUp() {
        filter = new InternalAuthFilter();
        chain = mock(FilterChain.class);
    }

    private void setConfiguredToken(String token) throws Exception {
        Field field = InternalAuthFilter.class.getDeclaredField("configuredToken");
        field.setAccessible(true);
        field.set(filter, token);
    }

    @Test
    void failsClosedWhenTokenNotConfigured() throws Exception {
        setConfiguredToken("");
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/validate/patch");
        request.addHeader("X-Internal-Token", "anything");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        verifyNoInteractions(chain);
    }

    @Test
    void rejectsMissingHeader() throws Exception {
        setConfiguredToken("real-secret");
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/validate/patch");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        verifyNoInteractions(chain);
    }

    @Test
    void rejectsWrongToken() throws Exception {
        setConfiguredToken("real-secret");
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/validate/patch");
        request.addHeader("X-Internal-Token", "wrong-secret");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        verifyNoInteractions(chain);
    }

    @Test
    void acceptsCorrectToken() throws Exception {
        setConfiguredToken("real-secret");
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/validate/patch");
        request.addHeader("X-Internal-Token", "real-secret");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
    }

    @Test
    void healthEndpointBypassesTheCheckEvenWithoutAToken() throws Exception {
        setConfiguredToken("real-secret");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/health");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
    }

    @Test
    void actuatorEndpointBypassesTheCheck() throws Exception {
        setConfiguredToken("real-secret");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/health");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
    }
}
