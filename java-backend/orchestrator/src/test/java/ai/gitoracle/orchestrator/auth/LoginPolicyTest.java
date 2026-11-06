package ai.gitoracle.orchestrator.auth;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LoginPolicyTest {

    private final LoginPolicy policy = new LoginPolicy(" Alice , bob ", "42, not-a-number");

    @Test
    void anInvitedLoginMayJoinWithoutAdminAndCaseDoesNotMatter() {
        LoginPolicy.Decision decision = policy.decide(7, "ALICE");

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.admin()).isFalse();
        assertThat(decision.scopes()).containsExactlyInAnyOrder("jobs:read", "jobs:write");
    }

    @Test
    void anAdminIsMatchedByIdWhateverTheLoginIs() {
        LoginPolicy.Decision decision = policy.decide(42, "renamed");

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.admin()).isTrue();
        assertThat(decision.scopes()).contains("platform:admin");
    }

    @Test
    void anAdminLoginAloneGrantsNothing() {
        LoginPolicy adminByLogin = new LoginPolicy("", "alice");

        assertThat(adminByLogin.decide(7, "alice").allowed()).isFalse();
    }

    @Test
    void everyoneElseIsRefused() {
        assertThat(policy.decide(7, "mallory").allowed()).isFalse();
        assertThat(policy.decide(7, null).allowed()).isFalse();
    }

    @Test
    void emptyListsAllowNobody() {
        assertThat(new LoginPolicy("", "").decide(42, "alice").allowed()).isFalse();
        assertThat(new LoginPolicy(null, null).decide(42, "alice").allowed()).isFalse();
    }
}
