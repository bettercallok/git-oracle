package ai.gitoracle.orchestrator.service;

import org.eclipse.jgit.api.Git;
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
     * Clones the given repository into a temporary workspace directory.
     * For public repositories no credentials are required.
     * GitHub App token auth will be added in Phase 3 (PR creation).
     *
     * @param repoUrl full HTTPS URL of the repository
     * @param jobId   the job ID used to create an isolated workspace directory
     * @return absolute path to the cloned repository
     */
    public String cloneRepository(String repoUrl, UUID jobId) {
        String clonePath = WORKSPACE_DIR + jobId.toString();
        File localPath = new File(clonePath);

        if (localPath.exists() && localPath.isDirectory()
                && localPath.list() != null && localPath.list().length > 0) {
            logger.info("Workspace already exists for job {}, reusing: {}", jobId, clonePath);
            return clonePath;
        }

        try {
            logger.info("Cloning {} into {} for job {}", repoUrl, clonePath, jobId);
            Git.cloneRepository()
                    .setURI(repoUrl)
                    .setDirectory(localPath)
                    .call()
                    .close();
            logger.info("Successfully cloned {} for job {}", repoUrl, jobId);
            return clonePath;
        } catch (Exception e) {
            logger.error("Failed to clone repository {} for job {}: {}", repoUrl, jobId, e.getMessage());
            // Return the path anyway so the pipeline continues; agents can handle missing workspace
            return clonePath;
        }
    }
}
