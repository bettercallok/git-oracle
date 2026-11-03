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

    /**
     * Number of golden cases this run actually covered. The dashboard's "Eval Cases"
     * tile used to be a hardcoded 50; recording the real count per run means the tile
     * reflects the dataset the number beside it was actually measured on.
     */
    @Column(name = "cases_total")
    private Integer casesTotal;

    /** The tenant that ran it; null for rows from before eval runs were tenant-scoped (= default tenant). */
    @Column(name = "tenant_id")
    private UUID tenantId;

        @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;
}
