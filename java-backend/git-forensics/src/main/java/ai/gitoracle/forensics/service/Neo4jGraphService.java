package ai.gitoracle.forensics.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import org.springframework.data.neo4j.core.Neo4jClient;
import java.util.List;

@Service
public class Neo4jGraphService {

    private static final Logger logger = LoggerFactory.getLogger(Neo4jGraphService.class);
    private final Neo4jClient neo4jClient;

    public Neo4jGraphService(Neo4jClient neo4jClient) {
        this.neo4jClient = neo4jClient;
    }

    public void populateGraph(String repoUrl, List<JGitService.CommitData> history) {
        logger.info("Populating Neo4j causal graph with {} commits from {}", history.size(), repoUrl);

        String repo = toRepoSlug(repoUrl);

        for (JGitService.CommitData commit : history) {
            String shortSha = commit.sha() != null && commit.sha().length() >= 7
                ? commit.sha().substring(0, 7) : commit.sha();
            String messageShort = commit.message() != null
                ? commit.message().split("\n", 2)[0] : "";

            String mergeCypher =
                "MERGE (d:Developer {name: $author}) " +
                "MERGE (c:Commit {sha: $sha}) " +
                "SET c.message = $message, c.message_short = $messageShort, " +
                "    c.short_sha = $shortSha, c.isBugFix = $isBugFix, c.repo = $repo " +
                "MERGE (d)-[:AUTHORED]->(c) ";

            neo4jClient.query(mergeCypher)
                .bind(commit.author()).to("author")
                .bind(commit.sha()).to("sha")
                .bind(commit.message()).to("message")
                .bind(messageShort).to("messageShort")
                .bind(shortSha).to("shortSha")
                .bind(commit.isBugFix()).to("isBugFix")
                .bind(repo).to("repo")
                .run();

            for (String file : commit.filesModified()) {
                // MERGE on (path, repo) to match the composite uniqueness constraint and
                // avoid collapsing same-named files from different repositories into one node.
                String fileCypher =
                    "MATCH (c:Commit {sha: $sha}) " +
                    "MERGE (f:File {path: $file, repo: $repo}) " +
                    "MERGE (c)-[:MODIFIED]->(f)";

                neo4jClient.query(fileCypher)
                    .bind(commit.sha()).to("sha")
                    .bind(file).to("file")
                    .bind(repo).to("repo")
                    .run();
            }
        }

        logger.info("Successfully ingested git blame data into Neo4j graph.");
    }

    /** Normalizes a git remote URL to an "org/repo" slug for graph node scoping. */
    private String toRepoSlug(String repoUrl) {
        if (repoUrl == null || repoUrl.isBlank()) return "unknown/repo";
        String slug = repoUrl
            .replaceFirst("^https?://[^/]+/", "")
            .replaceFirst("^git@[^:]+:", "")
            .replaceFirst("^file://", "")
            .replaceFirst("\\.git$", "");
        return slug.isBlank() ? repoUrl : slug;
    }
}
