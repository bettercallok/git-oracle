package ai.gitoracle.core.kafka;

public final class KafkaTopics {
    
    private KafkaTopics() {} // Prevent instantiation

    public static final String ERROR_INGESTED = "error-ingested";
    public static final String FORENSICS_COMPLETE = "forensics-complete";
    public static final String INVESTIGATION_COMPLETE = "investigation-complete";
    public static final String PLANNING_COMPLETE = "planning-complete";
    public static final String FIX_GENERATED = "fix-generated";
    public static final String TESTS_PASSED = "tests-passed";
    public static final String TESTS_FAILED = "tests-failed";
    public static final String PR_OPENED = "pr-opened";

    public static final String CONFIRMATION_REQUIRED = "confirmation-required";
    public static final String HUMAN_CONFIRMATION_RECEIVED = "human-confirmation-received";
    public static final String REVIEW_COMMENT_RECEIVED = "review-comment-received";
    public static final String REVIEW_REPLY_POSTED = "review-reply-posted";

    /** Outcomes of PRs GitOracle opened (merged / closed / approved / reverted),
     *  produced by the Error Ingestor's GitHub webhook handler. */
    public static final String GITHUB_PR_EVENTS = "github-pr-events";

    // ── Agent dispatch (Java <-> Python agents) ──────────────────────────
    // Dot-named, unlike the topics above, and until now only raw string
    // literals copied across OrchestratorService, DashboardController and
    // WebhookController. The Python side lists the same names in
    // ai_core/shared/topics.py; tests/test_topics_match_java.py fails if the
    // two drift apart.
    public static final String JOB_INVESTIGATE = "job.events.investigate";
    public static final String JOB_PLAN = "job.events.plan";
    public static final String JOB_FIX = "job.events.fix";
    public static final String JOB_REVIEW_RECEIVED = "job.events.review.received";
    /** Messages an agent could not process, with the error (ai_core/shared/kafka_consumer.py). */
    public static final String JOB_DLQ = "job.events.dlq";

    public static final String JOB_ESCALATED = "job-escalated";
    public static final String FEEDBACK_RECEIVED = "feedback-received";
    public static final String AUDIT_EVENT = "audit-event";
}
