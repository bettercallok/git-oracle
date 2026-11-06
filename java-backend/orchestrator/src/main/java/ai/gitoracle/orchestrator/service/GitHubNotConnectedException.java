package ai.gitoracle.orchestrator.service;

/** An organisation needs its own GitHub connection for this, and has none. */
public class GitHubNotConnectedException extends RuntimeException {
    public GitHubNotConnectedException() {
        super("This organisation hasn't connected GitHub yet. An owner can do that on the Repositories page.");
    }
}
