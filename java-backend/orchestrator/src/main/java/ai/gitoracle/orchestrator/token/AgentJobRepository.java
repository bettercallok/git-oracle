package ai.gitoracle.orchestrator.token;

import ai.gitoracle.core.model.postgres.AgentJob;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface AgentJobRepository extends JpaRepository<AgentJob, UUID> {

    /**
     * Atomically increments token_budget_used.
     * Returns the number of rows affected (0 if job not found).
     */
    @Modifying
    @Query("UPDATE AgentJob j SET j.tokenBudgetUsed = j.tokenBudgetUsed + :tokens WHERE j.id = :jobId")
    int incrementTokens(@Param("jobId") UUID jobId, @Param("tokens") int tokens);

    @Query("SELECT j.tokenBudgetUsed FROM AgentJob j WHERE j.id = :jobId")
    int getTokenBudgetUsed(@Param("jobId") UUID jobId);

    @Query("SELECT j.tokenBudgetLimit FROM AgentJob j WHERE j.id = :jobId")
    int getTokenBudgetLimit(@Param("jobId") UUID jobId);
}
