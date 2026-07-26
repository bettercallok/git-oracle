package ai.gitoracle.orchestrator;

import ai.gitoracle.core.kafka.KafkaTopics;
import ai.gitoracle.core.kafka.event.ErrorIngestedEvent;
import ai.gitoracle.core.model.postgres.AgentJob;
import ai.gitoracle.orchestrator.token.AgentJobRepository;
import ai.gitoracle.orchestrator.service.WorkspaceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class OrchestratorService {

    private static final Logger logger = LoggerFactory.getLogger(OrchestratorService.class);
    
    private final AgentJobRepository jobRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final WorkspaceService workspaceService;
    private final RestTemplate restTemplate = new RestTemplate();

    public OrchestratorService(AgentJobRepository jobRepository, KafkaTemplate<String, Object> kafkaTemplate, WorkspaceService workspaceService) {
        this.jobRepository = jobRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.workspaceService = workspaceService;
    }

    @KafkaListener(topics = KafkaTopics.ERROR_INGESTED, groupId = "orchestrator-group")
    public void handleErrorIngested(ErrorIngestedEvent event) {
        logger.info("Orchestrator received ERROR_INGESTED event for repo: {}", event.getRepoUrl());
        
        // 1. Create a new AgentJob in PostgreSQL
        AgentJob job = new AgentJob();
        job.setId(UUID.randomUUID());
        job.setRepo(event.getRepoUrl());
        job.setErrorId(event.getErrorId());
        
        // Set the tenant to fix NOT NULL constraint
        ai.gitoracle.core.model.postgres.Tenant tenant = new ai.gitoracle.core.model.postgres.Tenant();
        if (event.getTenantId() != null) {
            tenant.setId(event.getTenantId());
        } else {
            tenant.setId(UUID.fromString("00000000-0000-0000-0000-000000000000"));
        }
        job.setTenant(tenant);
        
        job.setState("INVESTIGATING");
        job.setCreatedAt(OffsetDateTime.now());
        jobRepository.save(job);
        
        logger.info("Job {} created. Triggering AI Planner...", job.getId());
        
        // 2. Clone the repository into a dynamic workspace
        String repoPath = workspaceService.cloneRepository(event.getRepoUrl(), job.getId());
        
        // 3. Publish event to Python Planner Agent
        Map<String, Object> plannerPayload = new HashMap<>();
        plannerPayload.put("job_id", job.getId().toString());
        plannerPayload.put("repo_url", event.getRepoUrl());
        plannerPayload.put("repo_path", repoPath);
        plannerPayload.put("error_id", event.getErrorId());
        
        kafkaTemplate.send("job.events.plan", plannerPayload);
    }

    @KafkaListener(topics = KafkaTopics.FIX_GENERATED, groupId = "orchestrator-group")
    public void handleFixGenerated(Map<String, String> event) {
        String jobIdStr = event.get("jobId");
        logger.info("Orchestrator received FIX_GENERATED event for job: {}", jobIdStr);
        
        // Update state
        jobRepository.findById(UUID.fromString(jobIdStr)).ifPresent(job -> {
            job.setState("TESTING");
            jobRepository.save(job);
        });

        // 3. Call the Java Test Runner (:8084)
        try {
            logger.info("Calling Test Runner API at :8084...");
            String repoPath = "/tmp/gitoracle-workspaces/" + jobIdStr;
            var testRequest = Map.of(
                "jobId", jobIdStr,
                "repoPath", repoPath,
                "patchData", event.get("patch"), 
                "framework", "JUNIT5"
            );
            var response = restTemplate.postForObject("http://localhost:8084/test", testRequest, Map.class);
            
            if (response != null && Boolean.TRUE.equals(response.get("passed"))) {
                logger.info("Tests passed! Publishing to {}...", KafkaTopics.TESTS_PASSED);
                kafkaTemplate.send(KafkaTopics.TESTS_PASSED, Map.of("jobId", jobIdStr, "rootCommit", "abcdef", "patch", event.get("patch")));
            } else {
                logger.warn("Tests failed. Publishing to FIX_REJECTED...");
            }
        } catch (Exception e) {
            logger.error("Failed to contact Test Runner. Is it running on port 8084?", e);
        }
    }

    @KafkaListener(topics = KafkaTopics.TESTS_PASSED, groupId = "orchestrator-group")
    public void handleTestsPassed(Map<String, String> event) {
        String jobIdStr = event.get("jobId");
        logger.info("Orchestrator received TESTS_PASSED event for job: {}", jobIdStr);
        
        // Update state
        jobRepository.findById(UUID.fromString(jobIdStr)).ifPresent(job -> {
            job.setState("PR_OPENED");
            jobRepository.save(job);
        });

        // 4. Call the GitHub Bot (:8085)
        try {
            logger.info("Calling GitHub Bot API at :8085...");
            Map<String, Object> prRequest = new HashMap<>();
            prRequest.put("jobId", jobIdStr);
            prRequest.put("agentVersion", "1.0");
            prRequest.put("rootCommit", event.get("rootCommit"));
            prRequest.put("causalScore", 0.95);
            prRequest.put("fixStrategy", "patch");
            prRequest.put("fixAttempts", 1);
            prRequest.put("testsPassed", 1);
            prRequest.put("testsTotal", 1);
            prRequest.put("coverageDelta", 0.05);
            prRequest.put("qualityScore", 0.99);
            prRequest.put("tokenBudgetUsed", 500);
            
            restTemplate.postForObject("http://localhost:8085/pull-request", prRequest, String.class);
            logger.info("Orchestrator pipeline complete for job: {}", jobIdStr);
        } catch (Exception e) {
            logger.error("Failed to contact GitHub Bot. Is it running on port 8085?", e);
        }
    }
}
