package com.gitoracle.testrunner;

public class TestRequest {
    private String tenantId;
    private String repoUrl;       // GitHub URL for cloning (e.g. https://github.com/owner/repo)
    private String repoPath;
    private String patchDiff;
    private String jobId;
    private String branch;        // branch to clone/test; empty = repo's default branch
    private TestFramework framework;

    public TestRequest() {}

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public String getRepoUrl() { return repoUrl; }
    public void setRepoUrl(String repoUrl) { this.repoUrl = repoUrl; }

    public String getBranch() { return branch; }
    public void setBranch(String branch) { this.branch = branch; }

    public String getRepoPath() { return repoPath; }
    public void setRepoPath(String repoPath) { this.repoPath = repoPath; }

    public String getPatchDiff() { return patchDiff; }
    public void setPatchDiff(String patchDiff) { this.patchDiff = patchDiff; }

    public String getJobId() { return jobId; }
    public void setJobId(String jobId) { this.jobId = jobId; }

    public TestFramework getFramework() { return framework; }
    public void setFramework(TestFramework framework) { this.framework = framework; }
}
