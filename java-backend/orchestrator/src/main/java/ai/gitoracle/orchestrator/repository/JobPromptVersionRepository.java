package ai.gitoracle.orchestrator.repository;

import ai.gitoracle.core.entity.JobPromptVersion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface JobPromptVersionRepository extends JpaRepository<JobPromptVersion, UUID> {

    Optional<JobPromptVersion> findByJobIdAndAgentName(UUID jobId, String agentName);

    /** Adds one LLM call's tokens to the agent's row for the job; 0 when the agent never registered a version. */
    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Modifying
    @Query("UPDATE JobPromptVersion v SET v.tokensUsed = COALESCE(v.tokensUsed, 0) + :tokens "
         + "WHERE v.jobId = :jobId AND v.agentName = :agentName")
    int addTokens(@org.springframework.data.repository.query.Param("jobId") UUID jobId,
                  @org.springframework.data.repository.query.Param("agentName") String agentName,
                  @org.springframework.data.repository.query.Param("tokens") int tokens);

    /**
     * Real per-(agent, version) performance, joined from the jobs that actually ran
     * on each prompt revision.
     *
     * Columns: agent_name, prompt_version, jobs, successes, avg_tokens, is_active.
     *
     * "Success" is defined as the job reaching PR_OPENED — i.e. the fix survived
     * guardrails, passed tests, and produced a real pull request — or PR_MERGED.
     * A PR a human closed without merging (PR_CLOSED) is finished but not a success. Jobs still in
     * flight (QUEUED / INVESTIGATING / PLANNING / TESTING) are excluded entirely
     * rather than counted as failures, so a version isn't penalised for work that
     * simply hasn't finished yet.
     *
     * ESCALATED is not finished either: a human can still approve it and the
     * job go on to open a PR. It was counted as a failure here while the jobs
     * page (rightly) treated it as still open.
     *
     * avg_tokens is this AGENT's tokens per job (job_prompt_versions.tokens_used),
     * not the job's total — which put the same figure on every agent's row. NULL,
     * not 0, when nothing was measured, so the UI can show "—" rather than "free".
     */
    @Query(value = """
        SELECT
            jpv.agent_name,
            jpv.prompt_version,
            COUNT(*)                                                        AS jobs,
            COUNT(*) FILTER (WHERE j.state IN ('PR_OPENED', 'PR_MERGED'))   AS successes,
            AVG(NULLIF(jpv.tokens_used, 0))                                 AS avg_tokens,
            COALESCE(bool_or(pv.is_active), false)                          AS is_active
        FROM job_prompt_versions jpv
        JOIN agent_job j ON j.id = jpv.job_id
        LEFT JOIN prompt_version pv
               ON pv.agent_name = jpv.agent_name
              AND pv.version    = jpv.prompt_version
              AND pv.prompt_key = 'system'
        WHERE j.state IN ('PR_OPENED', 'PR_MERGED', 'PR_CLOSED', 'FAILED')
          AND j.tenant_id = :tenantId
        GROUP BY jpv.agent_name, jpv.prompt_version
        ORDER BY jpv.agent_name, jpv.prompt_version DESC
        """, nativeQuery = true)
    List<Object[]> aggregatePerformance(@org.springframework.data.repository.query.Param("tenantId") UUID tenantId);
}
