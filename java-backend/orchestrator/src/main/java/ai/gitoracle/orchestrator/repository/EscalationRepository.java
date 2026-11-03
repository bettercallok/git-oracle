package ai.gitoracle.orchestrator.repository;

import ai.gitoracle.core.entity.Escalation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public interface EscalationRepository extends JpaRepository<Escalation, UUID> {

    boolean existsByJob_IdAndStatus(UUID jobId, String status);

    /**
     * Closes a job's still-pending escalations before a new one is queued. A job
     * can leave ESCALATED without a human resolving its row (the pipeline
     * resumes on its own) and escalate again later; the old row then stayed
     * PENDING beside the new one, and the job was listed — and counted — twice.
     */
    @org.springframework.transaction.annotation.Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = false)
    @Query("UPDATE Escalation e SET e.status = 'SUPERSEDED', e.resolvedAt = :now "
         + "WHERE e.job.id = :jobId AND e.status = 'PENDING'")
    int supersedePending(@Param("jobId") UUID jobId, @Param("now") OffsetDateTime now);

    /** ESCALATED jobs that nobody can act on: no PENDING row exists for them. */
    @Query("SELECT j.id FROM AgentJob j WHERE j.state = 'ESCALATED' AND NOT EXISTS "
         + "(SELECT e FROM Escalation e WHERE e.job = j AND e.status = 'PENDING')")
    List<UUID> escalatedJobsWithoutPendingEscalation();
}
