package ai.gitoracle.core.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "escalations")
@Data
public class Escalation {
    @Id
    private UUID id;

    @Column(name = "job_id")
    private UUID jobId;

    private String reason;
    
    @Column(name = "confidence_score")
    private Double confidenceScore;

    private String status;

    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "resolved_at")
    private OffsetDateTime resolvedAt;
}
