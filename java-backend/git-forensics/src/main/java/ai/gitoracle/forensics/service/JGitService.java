package ai.gitoracle.forensics.service;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

@Service
public class JGitService {

    private static final Logger logger = LoggerFactory.getLogger(JGitService.class);

    /**
     * Performs a shallow clone of the repository.
     * @param repoUrl The git repository URL.
     * @return The local directory where the repo was cloned.
     */
    public File shallowClone(String repoUrl) {
        try {
            Path tempDir = Files.createTempDirectory("gitoracle-repo-");
            logger.info("Cloning repository {} into {}", repoUrl, tempDir.toAbsolutePath());
            
            Git.cloneRepository()
                .setURI(repoUrl)
                .setDirectory(tempDir.toFile())
                .setDepth(50) // shallow clone
                .call();
                
            return tempDir.toFile();
        } catch (Exception e) {
            logger.error("Failed to clone repository: " + repoUrl, e);
            throw new RuntimeException("Git clone failed", e);
        }
    }
}
