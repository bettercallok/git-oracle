package ai.gitoracle.core.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "eval_runs")
@Data
public class EvalRun {
    @Id
    private UUID id;

    @Column(name = "golden_dataset_version")
    private String goldenDatasetVersion;

    private Double accuracy;
    
    @Column(name = "avg_latency_ms")
    private Integer avgLatencyMs;

    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;
}
