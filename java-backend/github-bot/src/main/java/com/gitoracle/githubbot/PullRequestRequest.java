package com.gitoracle.githubbot;

public class PullRequestRequest {
    private String jobId;
    private String repoFullName; // e.g. "omkhatri/test-repo"
    private String patchDiff;
    private String commitMessage;
    private String rootCommit;
    private double causalScore;
    private String fixStrategy;
    private int fixAttempts;
    private int testsPassed;
    private int testsTotal;
    private double coverageDelta;
    private double qualityScore;
    private int tokenBudgetUsed;
    private String causalChainNarrative;
    private String patchSummary;
    // Branch the fix was actually read from, tested against, and should be based
    // on. Empty/null = the repo's actual default branch. Without this, the fix
    // branch was always cut from whatever GitHub reports as the default branch,
    // and the PR was always opened against it — regardless of which branch the
    // rest of the pipeline used, which could mean applying a patch generated
    // against different file content, or landing the PR on the wrong branch
    // entirely.
    private String sourceBranch;

    public PullRequestRequest() {
    }

    public String getSourceBranch() {
        return sourceBranch;
    }

    public void setSourceBranch(String sourceBranch) {
        this.sourceBranch = sourceBranch;
    }

    public String getJobId() {
        return jobId;
    }

    public void setJobId(String jobId) {
        this.jobId = jobId;
    }

    public String getRepoFullName() {
        return repoFullName;
    }

    public void setRepoFullName(String repoFullName) {
        this.repoFullName = repoFullName;
    }

    public String getPatchDiff() {
        return patchDiff;
    }

    public void setPatchDiff(String patchDiff) {
        this.patchDiff = patchDiff;
    }

    public String getCommitMessage() {
        return commitMessage;
    }

    public void setCommitMessage(String commitMessage) {
        this.commitMessage = commitMessage;
    }

    public String getRootCommit() {
        return rootCommit;
    }

    public void setRootCommit(String rootCommit) {
        this.rootCommit = rootCommit;
    }

    public double getCausalScore() {
        return causalScore;
    }

    public void setCausalScore(double causalScore) {
        this.causalScore = causalScore;
    }

    public String getFixStrategy() {
        return fixStrategy;
    }

    public void setFixStrategy(String fixStrategy) {
        this.fixStrategy = fixStrategy;
    }

    public int getFixAttempts() {
        return fixAttempts;
    }

    public void setFixAttempts(int fixAttempts) {
        this.fixAttempts = fixAttempts;
    }

    public int getTestsPassed() {
        return testsPassed;
    }

    public void setTestsPassed(int testsPassed) {
        this.testsPassed = testsPassed;
    }

    public int getTestsTotal() {
        return testsTotal;
    }

    public void setTestsTotal(int testsTotal) {
        this.testsTotal = testsTotal;
    }

    public double getCoverageDelta() {
        return coverageDelta;
    }

    public void setCoverageDelta(double coverageDelta) {
        this.coverageDelta = coverageDelta;
    }

    public double getQualityScore() {
        return qualityScore;
    }

    public void setQualityScore(double qualityScore) {
        this.qualityScore = qualityScore;
    }

    public int getTokenBudgetUsed() {
        return tokenBudgetUsed;
    }

    public void setTokenBudgetUsed(int tokenBudgetUsed) {
        this.tokenBudgetUsed = tokenBudgetUsed;
    }

    public String getCausalChainNarrative() {
        return causalChainNarrative;
    }

    public void setCausalChainNarrative(String causalChainNarrative) {
        this.causalChainNarrative = causalChainNarrative;
    }

    public String getPatchSummary() {
        return patchSummary;
    }

    public void setPatchSummary(String patchSummary) {
        this.patchSummary = patchSummary;
    }
}
