package com.gitoracle.githubbot;

public class PullRequestRequest {
    private String jobId;
    private String repoFullName; // e.g. "omkhatri/test-repo"
    private String installationId; // whose GitHub connection acts; blank means the default one
    private String patchDiff;
    private String commitMessage;
    private String rootCommit;
    private Double causalScore;      // null: no investigation ran (direct fix)
    private String fixStrategy;
    private int fixAttempts;
    private Integer testsPassed;     // null: the runner found no test counts to read
    private Integer testsTotal;
    private Double coverageDelta;    // null: not measured (nothing measures it today)
    private Double qualityScore;     // null: no score to show
    // False when no test suite ran (the test runner's safe-pass): the fix passed
    // nothing because nothing checked it. Null, from an older sender, means verified.
    private Boolean testsVerified;
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
    // Set when this fix replaces one that already has a PR (a dashboard
    // "regenerate"): the new fix is committed onto that PR's branch instead of
    // opening a second PR for the same job. Null = open a new PR.
    private String existingPrUrl;

    public PullRequestRequest() {
    }

    public String getExistingPrUrl() {
        return existingPrUrl;
    }

    public void setExistingPrUrl(String existingPrUrl) {
        this.existingPrUrl = existingPrUrl;
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

    public Double getCausalScore() {
        return causalScore;
    }

    public void setCausalScore(Double causalScore) {
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

    public Integer getTestsPassed() {
        return testsPassed;
    }

    public void setTestsPassed(Integer testsPassed) {
        this.testsPassed = testsPassed;
    }

    public Integer getTestsTotal() {
        return testsTotal;
    }

    public void setTestsTotal(Integer testsTotal) {
        this.testsTotal = testsTotal;
    }

    public Double getCoverageDelta() {
        return coverageDelta;
    }

    public void setCoverageDelta(Double coverageDelta) {
        this.coverageDelta = coverageDelta;
    }

    public Double getQualityScore() {
        return qualityScore;
    }

    public void setQualityScore(Double qualityScore) {
        this.qualityScore = qualityScore;
    }

    public Boolean getTestsVerified() {
        return testsVerified;
    }

    public void setTestsVerified(Boolean testsVerified) {
        this.testsVerified = testsVerified;
    }

    /** Whether a test suite actually ran against this fix. */
    @com.fasterxml.jackson.annotation.JsonIgnore
    public boolean isVerified() {
        return !Boolean.FALSE.equals(testsVerified);
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

    public String getInstallationId() { return installationId; }
    public void setInstallationId(String installationId) { this.installationId = installationId; }
}
