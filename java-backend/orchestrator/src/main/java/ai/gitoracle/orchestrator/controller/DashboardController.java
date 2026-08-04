package ai.gitoracle.orchestrator.controller;

import ai.gitoracle.core.model.postgres.AgentJob;
import ai.gitoracle.core.entity.Escalation;
import ai.gitoracle.core.entity.EvalRun;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.transaction.annotation.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.persistence.EntityManager;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
@Transactional
@CrossOrigin(origins = "*", allowedHeaders = "*", methods = {RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT, RequestMethod.DELETE, RequestMethod.OPTIONS})
public class DashboardController {

    private static final Logger logger = LoggerFactory.getLogger(DashboardController.class);
    private final EntityManager entityManager;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    public DashboardController(EntityManager entityManager, KafkaTemplate<String, Object> kafkaTemplate) {
        this.entityManager = entityManager;
        this.kafkaTemplate = kafkaTemplate;
    }

    @GetMapping("/jobs")
    public ResponseEntity<List<AgentJob>> getJobs() {
        List<AgentJob> jobs = entityManager.createQuery("SELECT j FROM AgentJob j ORDER BY j.createdAt DESC", AgentJob.class)
            .setMaxResults(50)
            .getResultList();
        return ResponseEntity.ok(jobs);
    }

    @GetMapping("/jobs/{id}")
    public ResponseEntity<AgentJob> getJob(@PathVariable UUID id) {
        AgentJob job = entityManager.find(AgentJob.class, id);
        if (job == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(job);
    }

    @PostMapping("/jobs")
    public ResponseEntity<AgentJob> createJob(@RequestBody Map<String, String> request) {
        // CLI hits this endpoint
        AgentJob job = new AgentJob();
        job.setId(UUID.randomUUID());
        job.setRepo(request.get("repoUrl"));
        job.setState("QUEUED");
        job.setErrorId("manual-trigger");
        entityManager.persist(job);
        return ResponseEntity.ok(job);
    }

    /**
     * Dashboard "Regenerate Fix" button — user provides explicit instructions.
     * Directly publishes job.events.fix with human_instructions injected,
     * bypassing the Reviewer Agent (human intent is already explicit).
     */
    @PostMapping("/jobs/{jobId}/feedback")
    public ResponseEntity<Map<String, String>> submitFeedback(
            @PathVariable UUID jobId,
            @RequestBody Map<String, String> request) {

        AgentJob job = entityManager.find(AgentJob.class, jobId);
        if (job == null) return ResponseEntity.notFound().build();

        String instructions = request.get("instructions");
        if (instructions == null || instructions.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "instructions field is required"));
        }

        logger.info("Dashboard feedback received for job {} — triggering new fix cycle", jobId);

        // Update job state
        job.setState("REGENERATING");
        entityManager.merge(job);

        // Build a new fix event with the human's instructions
        String repoPath = "/tmp/gitoracle-workspaces/" + jobId;
        Map<String, Object> fixPayload = new HashMap<>();
        fixPayload.put("job_id", jobId.toString());
        fixPayload.put("repo_url", job.getRepo());
        fixPayload.put("repo_path", repoPath);
        fixPayload.put("error_id", job.getErrorId());
        fixPayload.put("human_instructions", instructions);
        // Minimal plan — fixer will use human instructions as primary guide
        Map<String, Object> plan = new HashMap<>();
        plan.put("strategy", "human_directed");
        plan.put("affected_files", List.of());
        plan.put("affected_functions", List.of());
        plan.put("max_lines_to_change", 200);
        plan.put("reasoning", "User requested regeneration via dashboard: " + instructions);
        plan.put("confidence", 1.0);
        fixPayload.put("plan", plan);

        kafkaTemplate.send("job.events.fix", fixPayload);

        return ResponseEntity.ok(Map.of("status", "REGENERATING", "jobId", jobId.toString()));
    }

    /**
     * POST /api/v1/trigger — "Ask GitOracle to fix" from the new FixCommand page.
     * Accepts a repo URL + issue description, creates a new job and fires the pipeline.
     */
    @PostMapping("/trigger")
    public ResponseEntity<Map<String, String>> triggerFix(@RequestBody Map<String, String> request) {
        String repoUrl = request.get("repoUrl");
        String issue   = request.get("issueDescription");
        String targetRepo = request.getOrDefault("targetRepo", "");
        if (repoUrl == null || issue == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "repoUrl and issueDescription are required"));
        }

        AgentJob job = new AgentJob();
        job.setRepo(repoUrl);
        job.setState("QUEUED");
        job.setErrorId("dashboard-trigger-" + System.currentTimeMillis());
        job.setCreatedAt(OffsetDateTime.now());
        ai.gitoracle.core.model.postgres.Tenant tenant = new ai.gitoracle.core.model.postgres.Tenant();
        tenant.setId(UUID.fromString("00000000-0000-0000-0000-000000000000"));
        job.setTenant(tenant);
        entityManager.persist(job);
        entityManager.flush(); // ensure ID is assigned

        logger.info("Dashboard trigger: new job {} for repo {}", job.getId(), repoUrl);

        // Publish directly to fix topic with human instructions
        String repoPath = "/tmp/gitoracle-workspaces/" + job.getId();
        Map<String, Object> fixPayload = new HashMap<>();
        fixPayload.put("job_id", job.getId().toString());
        fixPayload.put("repo_url", repoUrl);
        fixPayload.put("repo_path", repoPath);
        fixPayload.put("error_id", job.getErrorId());
        fixPayload.put("human_instructions", issue);
        fixPayload.put("target_repo", targetRepo);
        Map<String, Object> plan = new HashMap<>();
        plan.put("strategy", "dashboard_triggered");
        plan.put("affected_files", List.of());
        plan.put("affected_functions", List.of());
        plan.put("max_lines_to_change", 300);
        plan.put("reasoning", issue);
        plan.put("confidence", 1.0);
        fixPayload.put("plan", plan);

        kafkaTemplate.send("job.events.fix", fixPayload);

        return ResponseEntity.ok(Map.of(
            "status", "QUEUED",
            "jobId", job.getId().toString()
        ));
    }

    @GetMapping("/escalations")
    public ResponseEntity<List<Escalation>> getEscalations() {
        List<Escalation> escalations = entityManager.createQuery("SELECT e FROM Escalation e ORDER BY e.createdAt DESC", Escalation.class)
            .setMaxResults(50)
            .getResultList();
        return ResponseEntity.ok(escalations);
    }

    @PostMapping("/escalations/{id}/resolve")
    public ResponseEntity<Void> resolveEscalation(@PathVariable UUID id, @RequestBody Map<String, String> request) {
        Escalation escalation = entityManager.find(Escalation.class, id);
        if (escalation != null) {
            String action = request.get("action"); // 'approve' or 'reject'
            escalation.setStatus("approve".equals(action) ? "APPROVED" : "REJECTED");
            escalation.setResolvedAt(OffsetDateTime.now());
            entityManager.merge(escalation);
            
            AgentJob job = escalation.getJob();
            if (job != null) {
                if ("approve".equals(action)) {
                    // Resume the pipeline - push to plan phase
                    job.setState("PLANNING");
                    entityManager.merge(job);
                    
                    Map<String, Object> plannerPayload = new HashMap<>();
                    plannerPayload.put("job_id", job.getId().toString());
                    plannerPayload.put("repo_url", job.getRepo());
                    plannerPayload.put("repo_path", "/tmp/gitoracle-workspaces/" + job.getId());
                    plannerPayload.put("error_id", job.getErrorId());
                    
                    // We mock investigation result here for resume, in reality we'd pull from DB
                    Map<String, Object> invResult = new HashMap<>();
                    invResult.put("narrative", "Human approved escalation: " + escalation.getReason());
                    invResult.put("confidence_score", 1.0);
                    invResult.put("recommended_strategy", "human_approved_force");
                    plannerPayload.put("investigation_result", invResult);
                    
                    kafkaTemplate.send("job.events.plan", plannerPayload);
                } else {
                    job.setState("FAILED");
                    entityManager.merge(job);
                }
            }
        }
        return ResponseEntity.ok().build();
    }

    @PostMapping("/evals")
    public ResponseEntity<EvalRun> saveEval(@RequestBody Map<String, Object> request) {
        EvalRun eval = new EvalRun();
        eval.setId(UUID.randomUUID());
        eval.setGoldenDatasetVersion((String) request.getOrDefault("goldenDatasetVersion", "v1"));
        
        Object accObj = request.get("accuracy");
        if (accObj instanceof Number) {
            eval.setAccuracy(((Number) accObj).doubleValue());
        } else {
            eval.setAccuracy(0.0);
        }
        
        Object latObj = request.get("avgLatencyMs");
        if (latObj instanceof Number) {
            eval.setAvgLatencyMs(((Number) latObj).intValue());
        } else {
            eval.setAvgLatencyMs(0);
        }
        
        entityManager.persist(eval);
        return ResponseEntity.ok(eval);
    }

    @GetMapping("/evals")
    public ResponseEntity<List<EvalRun>> getEvals() {
        List<EvalRun> evals = entityManager.createQuery("SELECT e FROM EvalRun e ORDER BY e.createdAt DESC", EvalRun.class)
            .setMaxResults(50)
            .getResultList();
        return ResponseEntity.ok(evals);
    }
}
