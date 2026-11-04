package ai.gitoracle.orchestrator.service;

/** A request named a repository that is not on the caller's tenant allow-list. Mapped to 403. */
public class RepoNotAllowedException extends RuntimeException {
    public RepoNotAllowedException(String message) {
        super(message);
    }
}
