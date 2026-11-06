package ai.gitoracle.orchestrator.auth;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LoginPolicyTest {

    private final LoginPolicy policy = new LoginPolicy(" Alice , bob ", "42, not-a-number");

    @Test
    void anInvitedLoginIsMatchedWhateverItsCase() {
        assertThat(policy.isInvited("ALICE")).isTrue();
        assertThat(policy.isInvited("mallory")).isFalse();
        assertThat(policy.isInvited(null)).isFalse();
    }

    @Test
    void anAdminIsMatchedByIdNeverByLogin() {
        assertThat(policy.isPlatformAdmin(42)).isTrue();
        assertThat(policy.isPlatformAdmin(7)).isFalse();
        assertThat(new LoginPolicy("", "alice").isPlatformAdmin(7)).isFalse();
    }

    @Test
    void emptyListsAdmitNobody() {
        assertThat(new LoginPolicy("", "").admitsAnyone()).isFalse();
        assertThat(new LoginPolicy(null, null).admitsAnyone()).isFalse();
        assertThat(policy.admitsAnyone()).isTrue();
    }
}
