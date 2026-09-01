package ai.gitoracle.orchestrator.repository;

import ai.gitoracle.core.entity.PrOutcome;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface PrOutcomeRepository extends JpaRepository<PrOutcome, UUID> {

    boolean existsByJobIdAndOutcome(UUID jobId, String outcome);

    /**
     * One row per job, collapsed to that job's most significant outcome, so a PR
     * that was approved and later merged counts once (as MERGED) rather than
     * twice. Ranking: REVERTED > MERGED > CLOSED > APPROVED — a revert is the
     * strongest signal about a fix's real quality and must not be masked by the
     * merge that preceded it.
     *
     * <p>Scoped to one tenant through the owning job. Previously this counted
     * every PR outcome in the installation, so the dashboard's headline merge
     * rate was computed across all tenants' work and shown to each of them —
     * a small but genuine cross-tenant disclosure of how much work other
     * tenants do and how well it goes.
     */
    @Query(value = """
        SELECT outcome, COUNT(*) FROM (
            SELECT DISTINCT ON (po.job_id) po.job_id, po.outcome
            FROM pr_outcomes po
            JOIN agent_job j ON j.id = po.job_id
            WHERE j.tenant_id = :tenantId
            ORDER BY po.job_id, CASE po.outcome
                WHEN 'REVERTED' THEN 1
                WHEN 'MERGED'   THEN 2
                WHEN 'CLOSED'   THEN 3
                WHEN 'APPROVED' THEN 4
                ELSE 5 END
        ) per_job
        GROUP BY outcome
        """, nativeQuery = true)
    List<Object[]> countByDistinctJobOutcome(@org.springframework.data.repository.query.Param("tenantId") UUID tenantId);
}
