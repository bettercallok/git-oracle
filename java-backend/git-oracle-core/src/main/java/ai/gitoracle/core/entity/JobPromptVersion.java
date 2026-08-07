package ai.gitoracle.core.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Which prompt version each agent used on a given job.
 *
 * This is the link that made per-prompt performance measurable. The prompt_version
 * table records what the prompts *are*, and agent_job records how jobs *turned
 * out*, but nothing connected the two — so the dashboard's per-version accuracy
 * and token figures had no possible source and were hardcoded.
 *
 * One row per (job, agent): the fixer may run several ReAct attempts within a job
 * but they all use the same active prompt version, so attribution is per agent
 * rather than per LLM call.
 */
@Entity
@Table(
    name = "job_prompt_versions",
    uniqueConstraints = @UniqueConstraint(columnNames = {"job_id", "agent_name"})
)
@Data
public class JobPromptVersion {

    @Id
    private UUID id;

    @Column(name = "job_id", nullable = false)
    private UUID jobId;

    @Column(name = "agent_name", nullable = false, length = 64)
    private String agentName;

    @Column(name = "prompt_version", nullable = false)
    private Integer promptVersion;

    @Column(name = "created_at", insertable = false, updatable = false,
            columnDefinition = "timestamptz DEFAULT CURRENT_TIMESTAMP")
    private OffsetDateTime createdAt;
}
