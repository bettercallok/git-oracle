package com.gitoracle.githubbot.feedback;

public class PrEvent {
    private String jobId;
    private String type; // PR_MERGED, PR_CLOSED, COMMIT_REVERTED, REVIEW_APPROVED
    private String reviewer;

    // Getters and Setters
    public String getJobId() {
        return jobId;
    }

    public void setJobId(String jobId) {
        this.jobId = jobId;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getReviewer() {
        return reviewer;
    }

    public void setReviewer(String reviewer) {
        this.reviewer = reviewer;
    }
}
