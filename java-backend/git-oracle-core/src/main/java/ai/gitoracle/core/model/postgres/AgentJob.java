package ai.gitoracle.core.model.postgres;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "agent_job")
@Data
public class AgentJob {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tenant_id", nullable = false)
    private Tenant tenant;

    @Column(name = "error_id", nullable = false)
    private String errorId;

    @Column(nullable = false)
    private String repo;

    @Column(nullable = false)
    private String state;

    @Column(name = "root_commit")
    private String rootCommit;

    @Column(name = "fix_patch")
    private String fixPatch;

    @Column(name = "pr_url")
    private String prUrl;

    private Integer attempts = 0;

    @Column(name = "causal_score")
    private Double causalScore;

    @Column(name = "fix_strategy")
    private String fixStrategy;

    @Column(name = "quality_score")
    private Double qualityScore;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "raw_payload", columnDefinition = "jsonb")
    private String rawPayload;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "escalation_report", columnDefinition = "jsonb")
    private String escalationReport;

    @Column(name = "token_budget_used")
    private Integer tokenBudgetUsed = 0;

    @Column(name = "token_budget_limit")
    private Integer tokenBudgetLimit = 50000;

    @Column(name = "agent_version")
    private String agentVersion;

    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private OffsetDateTime updatedAt;
}
