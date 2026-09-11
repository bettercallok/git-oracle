package com.gitoracle.testrunner;

public class TestResult {
    private boolean allPassed;
    private double qualityScore;
    private double coverageDelta;
    private String logs;

    /**
     * Whether a test suite actually ran and reported on this patch.
     *
     * <p>{@code allPassed} alone cannot express the difference between "the
     * suite ran and everything passed" and "there was no suite, so nothing
     * contradicted the patch". Both were reported as {@code allPassed=true,
     * qualityScore=1.0}, so a fix verified by nothing looked exactly like a fix
     * verified by a full green run — and went on to open a pull request with
     * the same confidence.
     *
     * <p>That matters most for the case it is easiest to reach: a repository
     * with no tests, or one whose tests were removed. The safe-pass behaviour
     * is deliberate and stays (a repo without tests should not be permanently
     * unfixable), but the caller can now tell the two apart.
     *
     * <p>Added as a new field rather than by changing {@code allPassed}: the
     * orchestrator branches on {@code allPassed} today, and flipping its
     * meaning would silently stop every no-test repo mid-pipeline.
     */
    private boolean verified = true;

    public TestResult(boolean allPassed, double qualityScore, double coverageDelta, String logs) {
        this(allPassed, qualityScore, coverageDelta, logs, true);
    }

    public TestResult(boolean allPassed, double qualityScore, double coverageDelta, String logs, boolean verified) {
        this.allPassed = allPassed;
        this.qualityScore = qualityScore;
        this.coverageDelta = coverageDelta;
        this.logs = logs;
        this.verified = verified;
    }

    public boolean isVerified() { return verified; }
    public void setVerified(boolean verified) { this.verified = verified; }

    public boolean isAllPassed() { return allPassed; }
    public void setAllPassed(boolean allPassed) { this.allPassed = allPassed; }

    public double getQualityScore() { return qualityScore; }
    public void setQualityScore(double qualityScore) { this.qualityScore = qualityScore; }

    public double getCoverageDelta() { return coverageDelta; }
    public void setCoverageDelta(double coverageDelta) { this.coverageDelta = coverageDelta; }

    public String getLogs() { return logs; }
    public void setLogs(String logs) { this.logs = logs; }
}
