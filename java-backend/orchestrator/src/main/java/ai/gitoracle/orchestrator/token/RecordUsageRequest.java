package ai.gitoracle.orchestrator.token;

import lombok.Data;

/**
 * Request body for the budget record endpoint.
 */
@Data
public class RecordUsageRequest {
    private int tokensUsed;
    private String agentName; // e.g. "investigator", "fixer"
}
