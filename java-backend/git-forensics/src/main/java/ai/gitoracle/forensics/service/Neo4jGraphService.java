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
        
        for (JGitService.CommitData commit : history) {
            String mergeCypher = 
                "MERGE (d:Developer {name: $author}) " +
                "MERGE (c:Commit {sha: $sha}) " +
                "SET c.message = $message, c.isBugFix = $isBugFix " +
                "MERGE (d)-[:AUTHORED]->(c) ";

            neo4jClient.query(mergeCypher)
                .bind(commit.author()).to("author")
                .bind(commit.sha()).to("sha")
                .bind(commit.message()).to("message")
                .bind(commit.isBugFix()).to("isBugFix")
                .run();

            for (String file : commit.filesModified()) {
                String fileCypher = 
                    "MATCH (c:Commit {sha: $sha}) " +
                    "MERGE (f:File {path: $file}) " +
                    "MERGE (c)-[:MODIFIED]->(f)";
                
                neo4jClient.query(fileCypher)
                    .bind(commit.sha()).to("sha")
                    .bind(file).to("file")
                    .run();
            }
        }
        
        logger.info("Successfully ingested git blame data into Neo4j graph.");
    }
}
