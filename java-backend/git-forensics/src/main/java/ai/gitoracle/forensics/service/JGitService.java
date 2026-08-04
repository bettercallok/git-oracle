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
    public record CommitData(String sha, String author, String message, boolean isBugFix, java.util.List<String> filesModified) {}

    public java.util.List<CommitData> extractHistory(File repoDir) {
        java.util.List<CommitData> commits = new java.util.ArrayList<>();
        try {
            // git log --name-status --pretty=format:"COMMIT|%H|%an|%s"
            ProcessBuilder pb = new ProcessBuilder("git", "log", "--name-status", "--pretty=format:COMMIT|%H|%an|%s");
            pb.directory(repoDir);
            Process process = pb.start();
            
            try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(process.getInputStream()))) {
                String line;
                String currentSha = null;
                String currentAuthor = null;
                String currentMessage = null;
                boolean currentIsBugFix = false;
                java.util.List<String> currentFiles = new java.util.ArrayList<>();
                
                while ((line = reader.readLine()) != null) {
                    if (line.isEmpty()) continue;
                    
                    if (line.startsWith("COMMIT|")) {
                        // Save previous commit
                        if (currentSha != null) {
                            commits.add(new CommitData(currentSha, currentAuthor, currentMessage, currentIsBugFix, new java.util.ArrayList<>(currentFiles)));
                        }
                        
                        String[] parts = line.split("\\|", 4);
                        if (parts.length >= 4) {
                            currentSha = parts[1];
                            currentAuthor = parts[2];
                            currentMessage = parts[3];
                            String msgLower = currentMessage.toLowerCase();
                            currentIsBugFix = msgLower.contains("fix") || msgLower.contains("bug") || msgLower.contains("issue") || msgLower.contains("patch");
                            currentFiles.clear();
                        }
                    } else {
                        // File status line, e.g., "M\tsrc/main/java/..."
                        String[] parts = line.split("\\t");
                        if (parts.length >= 2) {
                            currentFiles.add(parts[1]);
                        }
                    }
                }
                // Save last commit
                if (currentSha != null) {
                    commits.add(new CommitData(currentSha, currentAuthor, currentMessage, currentIsBugFix, currentFiles));
                }
            }
            process.waitFor();
        } catch (Exception e) {
            logger.error("Failed to extract history", e);
        }
        return commits;
    }
}
