package com.gitoracle.testrunner;

public class TestResult {
    private boolean allPassed;
    private double qualityScore;
    private double coverageDelta;
    private String logs;

    public TestResult(boolean allPassed, double qualityScore, double coverageDelta, String logs) {
        this.allPassed = allPassed;
        this.qualityScore = qualityScore;
        this.coverageDelta = coverageDelta;
        this.logs = logs;
    }

    public boolean isAllPassed() { return allPassed; }
    public void setAllPassed(boolean allPassed) { this.allPassed = allPassed; }

    public double getQualityScore() { return qualityScore; }
    public void setQualityScore(double qualityScore) { this.qualityScore = qualityScore; }

    public double getCoverageDelta() { return coverageDelta; }
    public void setCoverageDelta(double coverageDelta) { this.coverageDelta = coverageDelta; }

    public String getLogs() { return logs; }
    public void setLogs(String logs) { this.logs = logs; }
}
