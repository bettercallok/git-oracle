package ai.gitoracle.forensics.service;

import ai.gitoracle.core.kafka.KafkaTopics;
import ai.gitoracle.core.kafka.event.ErrorIngestedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.Map;

@Service
public class ForensicsOrchestrator {

    private static final Logger logger = LoggerFactory.getLogger(ForensicsOrchestrator.class);
    private final JGitService jGitService;
    private final Neo4jGraphService neo4jGraphService;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    public ForensicsOrchestrator(JGitService jGitService, Neo4jGraphService neo4jGraphService, KafkaTemplate<String, Object> kafkaTemplate) {
        this.jGitService = jGitService;
        this.neo4jGraphService = neo4jGraphService;
        this.kafkaTemplate = kafkaTemplate;
    }

    @KafkaListener(topics = KafkaTopics.ERROR_INGESTED, groupId = "git-forensics-group")
    public void handleErrorIngested(ErrorIngestedEvent event) {
        logger.info("Forensics received ERROR_INGESTED for job {}", event.getJobId());
        
        try {
            // 1. Shallow clone the repo
            File repoDir = jGitService.shallowClone(event.getRepoUrl());
            
            // 2. Extract History
            java.util.List<JGitService.CommitData> history = jGitService.extractHistory(repoDir);
            
            // 3. Parse blame and populate Neo4j Graph
            neo4jGraphService.populateGraph(event.getRepoUrl(), history);
            
            // 4. Publish FORENSICS_COMPLETE (In a full implementation we'd pass the actual forensics payload)
            Map<String, Object> forensicsEvent = Map.of(
                "jobId", event.getJobId().toString(),
                "repoUrl", event.getRepoUrl(),
                "forensicsScore", 0.95
            );
            
            kafkaTemplate.send(KafkaTopics.FORENSICS_COMPLETE, forensicsEvent);
            logger.info("Published FORENSICS_COMPLETE for job {}", event.getJobId());
            
        } catch (Exception e) {
            logger.error("Failed to process git forensics for job " + event.getJobId(), e);
        }
    }
}
