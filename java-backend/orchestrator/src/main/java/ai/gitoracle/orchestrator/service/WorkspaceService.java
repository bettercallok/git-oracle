package ai.gitoracle.orchestrator.service;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.UUID;

@Service
public class WorkspaceService {

    private static final Logger logger = LoggerFactory.getLogger(WorkspaceService.class);
    private static final String WORKSPACE_DIR = "/tmp/gitoracle-workspaces/";

    public WorkspaceService() {
        new File(WORKSPACE_DIR).mkdirs();
    }

    /**
     * Clones the given repository's default branch into a temporary workspace
     * directory. Kept for callers with no branch selection.
     */
    public String cloneRepository(String repoUrl, UUID jobId) {
        return cloneRepository(repoUrl, null, jobId);
    }

    /**
     * Clones the given repository into a temporary workspace directory.
     * For public repositories no credentials are required.
     * GitHub App token auth will be added in Phase 3 (PR creation).
     *
     * @param repoUrl full HTTPS URL of the repository
     * @param branch  branch to check out, or null/blank for the repo's default branch
     * @param jobId   the job ID used to create an isolated workspace directory
     * @return absolute path to the cloned repository
     */
    public String cloneRepository(String repoUrl, String branch, UUID jobId) {
        String clonePath = WORKSPACE_DIR + jobId.toString();
        File localPath = new File(clonePath);

        if (localPath.exists() && localPath.isDirectory()
                && localPath.list() != null && localPath.list().length > 0) {
            logger.info("Workspace already exists for job {}, reusing: {}", jobId, clonePath);
            return clonePath;
        }

        try {
            logger.info("Cloning {} (branch: {}) into {} for job {}",
                        repoUrl, branch != null && !branch.isBlank() ? branch : "<default>", clonePath, jobId);
            var cloneCommand = Git.cloneRepository()
                    .setURI(repoUrl)
                    .setDirectory(localPath);
            if (branch != null && !branch.isBlank()) {
                cloneCommand.setBranch(branch);
            }
            cloneCommand.call().close();
            logger.info("Successfully cloned {} for job {}", repoUrl, jobId);
            return clonePath;
        } catch (Exception e) {
            logger.error("Failed to clone repository {} (branch: {}) for job {}: {}",
                         repoUrl, branch, jobId, e.getMessage());
            // Return the path anyway so the pipeline continues; agents can handle missing workspace
            return clonePath;
        }
    }

    /**
     * Resolves the HEAD commit SHA of an already-cloned workspace.
     * Returns null if the workspace doesn't exist or isn't a git repo (e.g. clone failed).
     */
    public String getHeadCommitSha(String clonePath) {
        try (Repository repo = new FileRepositoryBuilder()
                .setGitDir(new File(clonePath, ".git"))
                .build()) {
            ObjectId head = repo.resolve("HEAD");
            return head != null ? head.getName() : null;
        } catch (Exception e) {
            logger.warn("Could not resolve HEAD commit for workspace {}: {}", clonePath, e.getMessage());
            return null;
        }
    }
}
