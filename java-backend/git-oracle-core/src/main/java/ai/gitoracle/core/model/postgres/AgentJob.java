package ai.gitoracle.core.model.postgres;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.OffsetDateTime;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnore;

@Entity
@Table(name = "agent_job")
@Data
public class AgentJob {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tenant_id", nullable = true)
    private Tenant tenant;

    @Column(name = "error_id", nullable = false)
    private String errorId;

    @Column(nullable = false)
    private String repo;

    @Column(nullable = false)
    private String state;

    @Column(name = "root_commit")
    private String rootCommit;

    // TEXT, not the default varchar(255): this holds a full unified diff, which
    // exceeds 255 chars for all but the most trivial patch. Persisting it against
    // the default column type failed with "value too long for type character
    // varying(255)", which rolled back the whole handleFixGenerated transaction —
    // so the job never left QUEUED — and threw out of the Kafka listener, which
    // then burned its 10 retries and discarded the event, stranding the job
    // permanently. Confirmed live on eval case1_npe (job 6e08cd53).
    @Column(name = "fix_patch", columnDefinition = "TEXT")
    private String fixPatch;

    @Column(name = "pr_url")
    private String prUrl;

    // Comma-joined list of files the fixer was authorized to touch when it
    // produced fixPatch — resolved before the patch-generating LLM call ran
    // (the plan's affected_files, or a regex fallback over human instructions),
    // never the LLM's own after-the-fact claim about what it edited. This is
    // what guardrails' allowed_files check is actually validated against;
    // persisted here alongside the patch purely for audit/dashboard visibility.
    @Column(name = "authorized_files", columnDefinition = "TEXT")
    private String authorizedFiles;

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
    @Column(name = "investigation_result", columnDefinition = "jsonb")
    private String investigationResult;

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
