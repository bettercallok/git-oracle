package ai.gitoracle.core.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * What actually happened to a pull request GitOracle opened.
 *
 * Until this existed, nothing in the system recorded PR outcomes at all:
 * github-bot's PrOutcomeListener consumed a "github-pr-events" topic that no
 * producer ever published to, and FeedbackService only logged. The dashboard's
 * "PR Merge Rate" and "PR Feedback" therefore showed invented constants.
 *
 * Rows are appended as GitHub webhooks arrive, so a PR that is approved and then
 * merged produces two rows; the merge-rate aggregation counts distinct jobs by
 * their most significant outcome rather than counting raw rows.
 */
@Entity
@Table(name = "pr_outcomes")
@Data
public class PrOutcome {

    @Id
    private UUID id;

    /** The GitOracle job whose PR this describes. Extracted from the PR body's
     *  embedded <!-- gitoracle:job_id:... --> marker. */
    @Column(name = "job_id", nullable = false)
    private UUID jobId;

    @Column(name = "pr_url")
    private String prUrl;

    /** MERGED | CLOSED | APPROVED | REVERTED */
    @Column(nullable = false, length = 32)
    private String outcome;

    /** GitHub login of whoever merged/closed/approved it. */
    private String reviewer;

    /** Written by the database, not the entity (insertable=false) — so it needs an
     *  explicit DEFAULT, otherwise every row lands with created_at NULL. */
    @Column(name = "created_at", insertable = false, updatable = false,
            columnDefinition = "timestamptz DEFAULT CURRENT_TIMESTAMP")
    private OffsetDateTime createdAt;
}
