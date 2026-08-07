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

    /**
     * Real per-(agent, version) performance, joined from the jobs that actually ran
     * on each prompt revision.
     *
     * Columns: agent_name, prompt_version, jobs, successes, avg_tokens, is_active.
     *
     * "Success" is defined as the job reaching PR_OPENED — i.e. the fix survived
     * guardrails, passed tests, and produced a real pull request. Jobs still in
     * flight (QUEUED / INVESTIGATING / PLANNING / TESTING) are excluded entirely
     * rather than counted as failures, so a version isn't penalised for work that
     * simply hasn't finished yet.
     *
     * avg_tokens is NULL — not 0 — when no finished job on that version recorded
     * any token usage, so the UI can distinguish "not measured" from "free".
     */
    @Query(value = """
        SELECT
            jpv.agent_name,
            jpv.prompt_version,
            COUNT(*)                                                        AS jobs,
            COUNT(*) FILTER (WHERE j.state = 'PR_OPENED')                   AS successes,
            AVG(NULLIF(j.token_budget_used, 0))                             AS avg_tokens,
            COALESCE(bool_or(pv.is_active), false)                          AS is_active
        FROM job_prompt_versions jpv
        JOIN agent_job j ON j.id = jpv.job_id
        LEFT JOIN prompt_version pv
               ON pv.agent_name = jpv.agent_name
              AND pv.version    = jpv.prompt_version
              AND pv.prompt_key = 'system'
        WHERE j.state IN ('PR_OPENED', 'ESCALATED', 'FAILED')
        GROUP BY jpv.agent_name, jpv.prompt_version
        ORDER BY jpv.agent_name, jpv.prompt_version DESC
        """, nativeQuery = true)
    List<Object[]> aggregatePerformance();
}
