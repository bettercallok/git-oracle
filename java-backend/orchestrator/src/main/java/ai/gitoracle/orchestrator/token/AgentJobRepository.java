package ai.gitoracle.orchestrator.token;

import ai.gitoracle.core.model.postgres.AgentJob;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Repository
public interface AgentJobRepository extends JpaRepository<AgentJob, UUID> {

    /**
     * Atomically increments token_budget_used.
     * Returns the number of rows affected (0 if job not found).
     */
    // Native SQL: the column is updatable=false on the entity so that saving a
    // stale AgentJob can't overwrite the count, and this is its one writer.
    @Transactional
    @Modifying
    @Query(value = "UPDATE agent_job SET token_budget_used = COALESCE(token_budget_used, 0) + :tokens WHERE id = :jobId",
           nativeQuery = true)
    int incrementTokens(@Param("jobId") UUID jobId, @Param("tokens") int tokens);

    /**
     * Moves a job from one state to another only if it is still in the first
     * (compare-and-set). Returns 0 when another writer changed the state in
     * between; JobStateService then looks again instead of overwriting it.
     */
    @Transactional
    @Modifying(flushAutomatically = true)
    @Query(value = "UPDATE agent_job SET state = :to, state_reason = :reason, state_changed_at = :changedAt "
                 + "WHERE id = :jobId AND state = :from", nativeQuery = true)
    int moveState(@Param("jobId") UUID jobId, @Param("from") String from, @Param("to") String to,
                  @Param("reason") String reason, @Param("changedAt") java.time.OffsetDateTime changedAt);

    /** Stamps a newly inserted job's reason and time (the columns are not written by an entity save). */
    @Transactional
    @Modifying(flushAutomatically = true)
    @Query(value = "UPDATE agent_job SET state_reason = :reason, state_changed_at = :changedAt WHERE id = :jobId",
           nativeQuery = true)
    int stampCreated(@Param("jobId") UUID jobId, @Param("reason") String reason,
                     @Param("changedAt") java.time.OffsetDateTime changedAt);

    @Query("SELECT j.state FROM AgentJob j WHERE j.id = :jobId")
    String currentState(@Param("jobId") UUID jobId);

    /** Jobs whose PR is open, for PrStatusReconciler. */
    java.util.List<AgentJob> findByState(String state);

    /** Candidates for the stalled-job watchdog. */
    java.util.List<AgentJob> findByStateInAndStateChangedAtBefore(
            java.util.Collection<String> states, java.time.OffsetDateTime changedBefore);

    @Query("SELECT j.tokenBudgetUsed FROM AgentJob j WHERE j.id = :jobId")
    int getTokenBudgetUsed(@Param("jobId") UUID jobId);

    @Query("SELECT j.tokenBudgetLimit FROM AgentJob j WHERE j.id = :jobId")
    int getTokenBudgetLimit(@Param("jobId") UUID jobId);
}
