package ai.gitoracle.orchestrator.controller;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An agent error reaches the dashboard as the agent's own explanation, not as
 * Spring's exception text — which showed as
 * `502 Bad Gateway: "{\"detail\":\"LLM inference failed: ...\"}"`.
 */
class CommitAnalystErrorTest {

    @Test
    void extractsTheAgentsDetailWithoutItsWrapper() {
        String body = "{\"detail\":\"LLM inference failed: LLM provider rejected the request (400): "
            + "Failed to generate JSON. [json_validate_failed]\"}";
        assertThat(CommitController.agentErrorDetail(body))
            .isEqualTo("LLM provider rejected the request (400): Failed to generate JSON. [json_validate_failed]");
    }

    @Test
    void validationErrorListsAreKeptAsText() {
        assertThat(CommitController.agentErrorDetail("{\"detail\":[{\"msg\":\"field required\"}]}"))
            .contains("field required");
    }

    @Test
    void nonJsonBodiesAreReturnedAsIsAndEmptyOnesAsNull() {
        assertThat(CommitController.agentErrorDetail("Internal Server Error")).isEqualTo("Internal Server Error");
        assertThat(CommitController.agentErrorDetail("")).isNull();
        assertThat(CommitController.agentErrorDetail("{\"other\":1}")).isNull();
    }
}
