package ai.gitoracle.orchestrator.model;

public class TenantConfig {
    private String githubAppInstallationId;
    private int tokenBudget;
    private double riskScoreThreshold;
    private String escalationEmail;

    public TenantConfig() {}

    public String getGithubAppInstallationId() {
        return githubAppInstallationId;
    }

    public void setGithubAppInstallationId(String githubAppInstallationId) {
        this.githubAppInstallationId = githubAppInstallationId;
    }

    public int getTokenBudget() {
        return tokenBudget;
    }

    public void setTokenBudget(int tokenBudget) {
        this.tokenBudget = tokenBudget;
    }

    public double getRiskScoreThreshold() {
        return riskScoreThreshold;
    }

    public void setRiskScoreThreshold(double riskScoreThreshold) {
        this.riskScoreThreshold = riskScoreThreshold;
    }

    public String getEscalationEmail() {
        return escalationEmail;
    }

    public void setEscalationEmail(String escalationEmail) {
        this.escalationEmail = escalationEmail;
    }
}
