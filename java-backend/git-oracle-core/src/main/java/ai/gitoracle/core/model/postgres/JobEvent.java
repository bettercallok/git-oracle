package ai.gitoracle.core.model.postgres;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One entry in a job's timeline: a state change, or a progress note an agent
 * reported while working.
 *
 * <p>Before this existed a job carried only its current state, so "why is it
 * stuck?" meant reading every service's log. The first CI eval run sat in
 * INVESTIGATING for ten minutes with no way to tell, from the job itself,
 * whether the Investigator had crashed, was slow, or had finished long ago
 * (INVESTIGATING was never left on the normal path, even through fixing).
 */
@Entity
@Table(name = "job_event", indexes = @Index(name = "idx_job_event_job", columnList = "job_id, created_at"))
@Data
public class JobEvent {

    /** STATE_CHANGE: written by JobStateService. AGENT_PROGRESS / ERROR: reported by agents. */
    public enum Type { STATE_CHANGE, AGENT_PROGRESS, ERROR }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "job_id", nullable = false)
    private UUID jobId;

    @Column(name = "tenant_id")
    private UUID tenantId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private Type type;

    @Column(name = "from_state", length = 32)
    private String fromState;

    @Column(name = "to_state", length = 32)
    private String toState;

    /** Who caused it: "orchestrator", "watchdog", "investigator", "dashboard", ... */
    @Column(nullable = false, length = 64)
    private String actor;

    @Column(columnDefinition = "TEXT")
    private String message;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String details;

    // Set from the service's Clock rather than left to the DB, so events written
    // within one transaction still sort in the order they happened.
    @Column(name = "created_at", nullable = false,
            columnDefinition = "timestamptz DEFAULT CURRENT_TIMESTAMP")
    private OffsetDateTime createdAt;
}
