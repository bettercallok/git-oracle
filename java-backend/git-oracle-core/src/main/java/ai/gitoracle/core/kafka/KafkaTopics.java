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

    public static final String JOB_ESCALATED = "job-escalated";
    public static final String FEEDBACK_RECEIVED = "feedback-received";
}
