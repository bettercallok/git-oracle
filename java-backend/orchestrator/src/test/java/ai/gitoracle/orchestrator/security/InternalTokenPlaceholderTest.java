package ai.gitoracle.orchestrator.security;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InternalTokenPlaceholderTest {

    private static InternalAuthFilter filterWith(String token) {
        InternalAuthFilter filter = new InternalAuthFilter();
        ReflectionTestUtils.setField(filter, "configuredToken", token);
        return filter;
    }

    @Test
    void theExamplePlaceholderRefusesToStart() {
        assertThatThrownBy(() -> filterWith("change_me_internal_service_token").requireInternalTokenConfigured())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("placeholder");
    }

    @Test
    void aRealTokenStarts() {
        assertThatCode(() -> filterWith("a1b2c3d4e5f6").requireInternalTokenConfigured()).doesNotThrowAnyException();
    }
}
