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
     */
    @Query(value = """
        SELECT outcome, COUNT(*) FROM (
            SELECT DISTINCT ON (job_id) job_id, outcome
            FROM pr_outcomes
            ORDER BY job_id, CASE outcome
                WHEN 'REVERTED' THEN 1
                WHEN 'MERGED'   THEN 2
                WHEN 'CLOSED'   THEN 3
                WHEN 'APPROVED' THEN 4
                ELSE 5 END
        ) per_job
        GROUP BY outcome
        """, nativeQuery = true)
    List<Object[]> countByDistinctJobOutcome();
}
