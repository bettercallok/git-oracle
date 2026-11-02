package ai.gitoracle.core.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "escalations")
@Data
public class Escalation {
    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "job_id")
    private ai.gitoracle.core.model.postgres.AgentJob job;

    // TEXT: the fixer sends its whole escalation report here, which routinely
    // exceeds the JPA default varchar(255) and would fail the insert.
    @Column(columnDefinition = "TEXT")
    private String reason;

    @Column(name = "confidence_score")
    private Double confidenceScore;

    private String status;

    // Was insertable=false with no DB default, so every escalation had a NULL
    // created_at and the queue's ORDER BY created_at DESC was arbitrary.
    @CreationTimestamp
    @Column(name = "created_at", updatable = false,
            columnDefinition = "timestamptz DEFAULT CURRENT_TIMESTAMP")
    private OffsetDateTime createdAt;

    @Column(name = "resolved_at")
    private OffsetDateTime resolvedAt;
}
