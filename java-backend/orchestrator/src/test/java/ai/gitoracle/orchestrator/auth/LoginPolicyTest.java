package ai.gitoracle.orchestrator.auth;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LoginPolicyTest {

    private final LoginPolicy policy = new LoginPolicy(" Alice , bob ", "42, not-a-number");

    @Test
    void anInvitedLoginIsMatchedWhateverItsCase() {
        assertThat(policy.isInvited(1, "ALICE")).isTrue();
        assertThat(policy.isInvited(1, "mallory")).isFalse();
        assertThat(policy.isInvited(1, null)).isFalse();
    }

    @Test
    void anInvitedIdIsMatchedAsAnIdWhateverTheLogin() {
        LoginPolicy byId = new LoginPolicy("1234, carol", "");
        assertThat(byId.isInvited(1234, "renamed-user")).isTrue();
        assertThat(byId.isInvited(99, "1234")).isFalse();
        assertThat(byId.isInvited(99, "Carol")).isTrue();
        assertThat(new LoginPolicy("1234", "").admitsAnyone()).isTrue();
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
