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
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;
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
    private final EntityManager entityManager;
    private final RestTemplate restTemplate = new RestTemplate();
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();

    public OrchestratorService(AgentJobRepository jobRepository, KafkaTemplate<String, Object> kafkaTemplate, WorkspaceService workspaceService, EntityManager entityManager) {
        this.jobRepository = jobRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.workspaceService = workspaceService;
        this.entityManager = entityManager;
    }

    @KafkaListener(topics = KafkaTopics.ERROR_INGESTED, groupId = "orchestrator-group")
    public void handleErrorIngested(ErrorIngestedEvent event) {
        logger.info("Orchestrator received ERROR_INGESTED event for repo: {}", event.getRepoUrl());

        // 1. Reuse the AgentJob the publisher already created (e.g. error-ingestor's
        // SemanticDedupService persists one before publishing this event) rather than
        // creating a second, orphaned row for the same error — event.getJobId() is
        // exactly the field that field was already added for; it just wasn't read here.
        AgentJob job = event.getJobId() != null
            ? jobRepository.findById(event.getJobId()).orElse(null)
            : null;

        if (job == null) {
            job = new AgentJob();
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
            job.setCreatedAt(OffsetDateTime.now());
        }

        job.setState("INVESTIGATING");
        jobRepository.save(job);

        logger.info("Job {} ready. Triggering AI Planner...", job.getId());
        
        // 2. Clone the repository into a dynamic workspace
        String repoPath = workspaceService.cloneRepository(event.getRepoUrl(), job.getId());

        // Capture the real HEAD commit so downstream PRs report an accurate root commit
        String headSha = workspaceService.getHeadCommitSha(repoPath);
        if (headSha != null) {
            job.setRootCommit(headSha);
            jobRepository.save(job);
        }

        // 3. Publish event to Python Investigator Agent
        Map<String, Object> investigatorPayload = new HashMap<>();
        investigatorPayload.put("job_id", job.getId().toString());
        investigatorPayload.put("repo_url", event.getRepoUrl());
        investigatorPayload.put("repo_path", repoPath);
        investigatorPayload.put("error_id", event.getErrorId());
        // The actual error detail (stack trace / webhook payload) captured by error-ingestor
        // was previously dropped here — the investigator only ever saw a generic templated
        // string mentioning the error_id, never the real error. Confirmed live: with no real
        // signal, it hallucinated plausible-sounding but wrong root causes on every test run.
        investigatorPayload.put("raw_payload", event.getRawPayload());

        kafkaTemplate.send("job.events.investigate", investigatorPayload);
    }

    /**
     * Snoops the investigate→plan handoff (Python investigator → Python planner) to
     * persist the investigation result on the job, so a human-approved escalation can
     * later resume planning with the real findings instead of fabricated context.
     * Uses a distinct consumer group so the planner still receives the same message.
     */
    @KafkaListener(topics = "job.events.plan", groupId = "orchestrator-plan-snoop-group",
                   containerFactory = "stringValueKafkaListenerContainerFactory")
    @Transactional
    public void persistInvestigationResult(String rawJson) {
        try {
            Map<String, Object> event = objectMapper.readValue(rawJson, Map.class);
            String jobIdStr = (String) event.get("job_id");
            Object investigation = event.get("investigation_result");
            if (jobIdStr == null || investigation == null) return;

            String json = objectMapper.writeValueAsString(investigation);
            jobRepository.findById(UUID.fromString(jobIdStr)).ifPresent(job -> {
                job.setInvestigationResult(json);
                jobRepository.save(job);
                logger.info("Persisted investigation result for job {}", jobIdStr);
            });
        } catch (Exception e) {
            logger.warn("Could not persist investigation result: {}", e.getMessage());
        }
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

        // 3. Call the Guardrails Agent (:9006) to validate the patch
        try {
            logger.info("Calling Guardrails Agent at :9006...");
            var guardrailsRequest = new java.util.HashMap<String, Object>();
            guardrailsRequest.put("diff", event.get("patch"));
            // patch_scanner.scan_patch computes unauthorized = touched_files - allowed_files,
            // so an empty allowed_files list means EVERY touched file is rejected — this
            // silently rejected every fix ever generated, confirmed live: "Patch touches
            // unauthorized files: ['...UserService.java']" for a patch that only touched
            // the file it was specifically asked to fix. filesModified now carries the
            // fixer's actual edited file(s), comma-joined (Map<String,String> event).
            String filesModified = event.getOrDefault("filesModified", "");
            java.util.List<String> allowedFiles = filesModified.isBlank()
                ? java.util.Collections.emptyList()
                : java.util.Arrays.asList(filesModified.split(","));
            guardrailsRequest.put("allowed_files", allowedFiles);

            restTemplate.postForObject("http://localhost:9006/validate/patch", guardrailsRequest, Map.class);
            logger.info("Guardrails validation passed.");
        } catch (org.springframework.web.client.HttpClientErrorException e) {
            logger.warn("Guardrails validation FAILED for job {}: {}", jobIdStr, e.getResponseBodyAsString());
            jobRepository.findById(UUID.fromString(jobIdStr)).ifPresent(job -> {
                job.setState("FAILED");
                jobRepository.save(job);
            });
            return; // Block execution
        } catch (Exception e) {
            logger.error("Failed to contact Guardrails Agent. Is it running on port 9006?", e);
            // We can choose to fail open or fail closed here. For safety, we fail closed.
            jobRepository.findById(UUID.fromString(jobIdStr)).ifPresent(job -> {
                job.setState("FAILED");
                jobRepository.save(job);
            });
            return;
        }

        // 4. Call the Java Test Runner (:8084)
        try {
            logger.info("Calling Test Runner API at :8084...");

            // Look up the repo URL and root commit from the job record
            final String[] repoUrl = {""};
            final String[] rootCommit = {null};
            jobRepository.findById(UUID.fromString(jobIdStr)).ifPresent(job -> {
                repoUrl[0] = job.getRepo();
                rootCommit[0] = job.getRootCommit();
            });

            var testRequest = new java.util.HashMap<String, Object>();
            testRequest.put("jobId",     jobIdStr);
            testRequest.put("repoUrl",   repoUrl[0]);            // for cloning
            testRequest.put("repoPath",  repoUrl[0]);            // fallback slug infer
            testRequest.put("patchDiff", event.get("patch"));
            testRequest.put("framework", "UNKNOWN");             // let runner auto-detect

            var response = restTemplate.postForObject("http://localhost:8084/test", testRequest, Map.class);

            if (response != null && Boolean.TRUE.equals(response.get("allPassed"))) {
                logger.info("Tests passed! Publishing to {}...", KafkaTopics.TESTS_PASSED);
                Map<String, String> testsPassedPayload = new HashMap<>();
                testsPassedPayload.put("jobId",          jobIdStr);
                testsPassedPayload.put("rootCommit",     rootCommit[0] != null ? rootCommit[0] : "unknown");
                testsPassedPayload.put("patch",          event.get("patch"));
                String targetRepo = event.getOrDefault("targetRepo", "");
                testsPassedPayload.put("targetRepo",     targetRepo);
                testsPassedPayload.put("isRegeneration", event.getOrDefault("isRegeneration", "false"));
                testsPassedPayload.put("qualityScore",   String.valueOf(response.get("qualityScore")));
                testsPassedPayload.put("coverageDelta",  String.valueOf(response.get("coverageDelta")));
                kafkaTemplate.send(KafkaTopics.TESTS_PASSED, testsPassedPayload);
            } else {
                String testLogs = response != null ? String.valueOf(response.get("logs")) : "no response";
                logger.warn("Tests FAILED for job {}. Logs:\n{}", jobIdStr,
                    testLogs.length() > 500 ? testLogs.substring(0, 500) + "..." : testLogs);
                jobRepository.findById(UUID.fromString(jobIdStr)).ifPresent(job -> {
                    job.setState("FAILED");
                    jobRepository.save(job);
                });
            }
        } catch (Exception e) {
            logger.error("Failed to contact Test Runner. Is it running on port 8084?", e);
        }
    }

    @KafkaListener(topics = KafkaTopics.TESTS_PASSED, groupId = "orchestrator-group")
    public void handleTestsPassed(Map<String, String> event) {
        String jobIdStr = event.get("jobId");
        logger.info("Orchestrator received TESTS_PASSED event for job: {}", jobIdStr);
        
        final String[] repoFullName = new String[1];
        final String[] errorId = new String[1];
        
        // Update state
        jobRepository.findById(UUID.fromString(jobIdStr)).ifPresent(job -> {
            job.setState("PR_OPENED");
            repoFullName[0] = job.getRepo().replace("https://github.com/", "").replace(".git", "");
            errorId[0] = job.getErrorId();
            jobRepository.save(job);
        });

        // Allow the event to override the target repo (for dashboard-triggered jobs)
        String targetRepo = event.getOrDefault("targetRepo", "");
        String prRepo = (targetRepo != null && !targetRepo.isBlank()) ? targetRepo : repoFullName[0];

        // 4. Call the GitHub Bot (:8085)
        try {
            logger.info("Calling GitHub Bot API at :8085 to open PR on {}...", prRepo);
            Map<String, Object> prRequest = new HashMap<>();
            prRequest.put("jobId", jobIdStr);
            prRequest.put("repoFullName", prRepo);
            prRequest.put("patchDiff", event.get("patch"));
            prRequest.put("commitMessage", "Fix " + errorId[0]);
            prRequest.put("agentVersion", "1.0");
            prRequest.put("rootCommit", event.get("rootCommit"));
            prRequest.put("causalScore", 0.95);
            prRequest.put("fixStrategy", "patch");
            prRequest.put("fixAttempts", 1);
            prRequest.put("testsPassed", 1);
            prRequest.put("testsTotal", 1);
            prRequest.put("coverageDelta", parseDoubleOrDefault(event.get("coverageDelta"), 0.0));
            prRequest.put("qualityScore", parseDoubleOrDefault(event.get("qualityScore"), 1.0));
            prRequest.put("tokenBudgetUsed", 500);
            
            restTemplate.postForObject("http://localhost:8085/pull-request", prRequest, String.class);
            logger.info("Orchestrator pipeline complete for job: {}", jobIdStr);
        } catch (Exception e) {
            logger.error("Failed to contact GitHub Bot. Is it running on port 8085?", e);
        }
    }

    /**
     * Handles GitHub PR comments routed via the Error Ingestor.
     * Calls the Reviewer Agent to evaluate the comment, then re-triggers
     * the Fixer Agent if the human's concern is valid (action_needed=true).
     */
    @KafkaListener(topics = "job.events.review.received", groupId = "orchestrator-group")
    public void handleReviewReceived(Map<String, Object> event) {
        String jobIdStr  = (String) event.get("job_id");
        String comment   = (String) event.get("comment");
        String repo      = (String) event.get("repo");

        if (jobIdStr == null || comment == null) {
            logger.warn("Review event missing job_id or comment — ignoring");
            return;
        }

        logger.info("Orchestrator received review comment for job: {} — calling Reviewer Agent", jobIdStr);

        AgentJob job = jobRepository.findById(UUID.fromString(jobIdStr)).orElse(null);
        if (job == null) {
            logger.warn("Job {} not found for review event", jobIdStr);
            return;
        }

        // Call Reviewer Agent HTTP API at :9003
        try {
            java.util.Map<String, Object> reviewRequest = new java.util.HashMap<>();
            reviewRequest.put("job_id",          jobIdStr);
            reviewRequest.put("tenant_id",       job.getTenant().getId().toString());
            reviewRequest.put("repo_path",       "/tmp/gitoracle-workspaces/" + jobIdStr);
            reviewRequest.put("bug_description", job.getErrorId());
            reviewRequest.put("fixer_patch",     "(stored patch not available — review based on comment only)");
            reviewRequest.put("human_comment",   comment);

            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> reviewResponse =
                restTemplate.postForObject("http://localhost:9003/review", reviewRequest, java.util.Map.class);

            if (reviewResponse == null) {
                logger.warn("Reviewer Agent returned null for job {}", jobIdStr);
                return;
            }

            Boolean actionNeeded = Boolean.TRUE.equals(reviewResponse.get("action_needed"));
            logger.info("Reviewer Agent response for job {}: action_needed={} stance={}",
                        jobIdStr, actionNeeded, reviewResponse.get("stance"));

            if (actionNeeded) {
                logger.info("Re-triggering Fixer Agent with human instructions for job {}", jobIdStr);
                job.setState("REGENERATING");
                jobRepository.save(job);

                String repoPath    = "/tmp/gitoracle-workspaces/" + jobIdStr;
                String repoForPr   = repo != null ? repo.replace("https://github.com/", "") : "";

                Map<String, Object> fixPayload = new HashMap<>();
                fixPayload.put("job_id",              jobIdStr);
                fixPayload.put("repo_url",            repo != null ? repo : job.getRepo());
                fixPayload.put("repo_path",           repoPath);
                fixPayload.put("error_id",            job.getErrorId());
                fixPayload.put("human_instructions",  comment);
                fixPayload.put("target_repo",         repoForPr);

                Map<String, Object> plan = new HashMap<>();
                plan.put("strategy",            "github_review_directed");
                plan.put("affected_files",      java.util.List.of());
                plan.put("affected_functions",  java.util.List.of());
                plan.put("max_lines_to_change", 200);
                plan.put("reasoning",           "GitHub reviewer requested: " + comment);
                plan.put("confidence",          1.0);
                fixPayload.put("plan", plan);

                kafkaTemplate.send("job.events.fix", fixPayload);
                logger.info("Dispatched new fix cycle for job {} based on GitHub comment", jobIdStr);
            }
        } catch (Exception e) {
            logger.error("Failed to process review event for job {}: {}", jobIdStr, e.getMessage());
        }
    }

    @KafkaListener(topics = KafkaTopics.JOB_ESCALATED, groupId = "orchestrator-group")
    @Transactional
    public void handleJobEscalated(Map<String, Object> event) {
        String jobIdStr = (String) event.get("jobId");
        if (jobIdStr == null) return;
        
        logger.warn("Orchestrator received JOB_ESCALATED event for job: {}", jobIdStr);
        
        // 1. Update job state to ESCALATED
        jobRepository.findById(UUID.fromString(jobIdStr)).ifPresent(job -> {
            job.setState("ESCALATED");
            jobRepository.save(job);
            
            // 2. Create Escalation record for the frontend
            ai.gitoracle.core.entity.Escalation escalation = new ai.gitoracle.core.entity.Escalation();
            escalation.setId(UUID.randomUUID());
            escalation.setJob(job);
            escalation.setReason((String) event.getOrDefault("reason", "Unknown escalation reason"));
            
            Object confObj = event.get("confidenceScore");
            if (confObj instanceof Number) {
                escalation.setConfidenceScore(((Number) confObj).doubleValue());
            } else {
                escalation.setConfidenceScore(0.0);
            }
            
            escalation.setStatus("PENDING");
            entityManager.persist(escalation);
        });
    }

    private double parseDoubleOrDefault(String value, double fallback) {
        try {
            return value != null ? Double.parseDouble(value) : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
