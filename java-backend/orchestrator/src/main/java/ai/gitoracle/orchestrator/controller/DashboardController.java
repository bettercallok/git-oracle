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

// No @CrossOrigin here: the dashboard reaches this exclusively through the API
// Gateway (:8080), whose globalcors config already sets Access-Control-Allow-Origin
// for every /api/v1/** route. Confirmed live: with both layers setting it, the
// header arrived duplicated ("http://localhost:5173, http://localhost:5173"),
// which is spec-invalid and browsers reject outright — every dashboard page that
// hit this controller through the gateway failed with a CORS error even though
// curl (which doesn't enforce CORS) saw a normal 200. Direct callers that bypass
// the gateway (eval harness, agents) are server-to-server and don't need CORS
// headers at all.
@RestController
@RequestMapping("/api/v1")
@Transactional
public class DashboardController {

    private static final Logger logger = LoggerFactory.getLogger(DashboardController.class);
    private final EntityManager entityManager;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ai.gitoracle.orchestrator.repository.PrOutcomeRepository prOutcomeRepository;
    private final ai.gitoracle.orchestrator.repository.JobPromptVersionRepository jobPromptVersionRepository;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();

    public DashboardController(EntityManager entityManager, KafkaTemplate<String, Object> kafkaTemplate,
                               ai.gitoracle.orchestrator.repository.PrOutcomeRepository prOutcomeRepository,
                               ai.gitoracle.orchestrator.repository.JobPromptVersionRepository jobPromptVersionRepository) {
        this.entityManager = entityManager;
        this.kafkaTemplate = kafkaTemplate;
        this.prOutcomeRepository = prOutcomeRepository;
        this.jobPromptVersionRepository = jobPromptVersionRepository;
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

                    // Resume with the real investigation result stored during the original run.
                    // Fall back to a minimal synthetic one only if nothing was persisted.
                    Map<String, Object> invResult = null;
                    if (job.getInvestigationResult() != null) {
                        try {
                            invResult = objectMapper.readValue(
                                job.getInvestigationResult(),
                                new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
                        } catch (Exception e) {
                            logger.warn("Could not parse stored investigation result for job {}: {}",
                                job.getId(), e.getMessage());
                        }
                    }
                    if (invResult == null) {
                        invResult = new HashMap<>();
                        invResult.put("narrative", "No stored investigation found; human approved escalation: " + escalation.getReason());
                        invResult.put("confidence_score", 1.0);
                        invResult.put("recommended_strategy", "human_approved_force");
                    }
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

        Object casesObj = request.get("casesTotal");
        if (casesObj instanceof Number) {
            eval.setCasesTotal(((Number) casesObj).intValue());
        } else {
            eval.setCasesTotal(0);
        }

        entityManager.persist(eval);
        return ResponseEntity.ok(eval);
    }

    /**
     * Real PR outcome breakdown, replacing the dashboard's previously hardcoded
     * "PR Merge Rate: 92%" and 72/18/7/3 feedback split.
     *
     * `total` counts distinct jobs that reached a PR and got some outcome — so a
     * merge rate is only meaningful once PRs are actually being merged/closed. When
     * nothing has been recorded yet the response is all zeros with total=0, which
     * the UI renders as "no data" rather than inventing a number.
     */
    @GetMapping("/pr-outcomes/stats")
    public ResponseEntity<Map<String, Object>> getPrOutcomeStats() {
        Map<String, Long> counts = new HashMap<>();
        for (String k : List.of("MERGED", "CLOSED", "APPROVED", "REVERTED")) {
            counts.put(k, 0L);
        }
        for (Object[] row : prOutcomeRepository.countByDistinctJobOutcome()) {
            counts.put((String) row[0], ((Number) row[1]).longValue());
        }

        long total = counts.values().stream().mapToLong(Long::longValue).sum();
        Map<String, Object> body = new HashMap<>();
        body.put("counts", counts);
        body.put("total", total);
        body.put("mergeRate", total == 0 ? null : (double) counts.get("MERGED") / total);
        return ResponseEntity.ok(body);
    }

    /**
     * Agents call this after resolving their system prompt, so the version they ran
     * on can be attributed to the job's eventual outcome. Idempotent per
     * (job, agent) — an agent that retries within a job still used one version.
     */
    @PostMapping("/jobs/{jobId}/prompt-version")
    public ResponseEntity<Void> recordPromptVersion(
            @PathVariable UUID jobId,
            @RequestBody Map<String, Object> request) {

        String agentName = (String) request.get("agentName");
        Object versionObj = request.get("version");
        if (agentName == null || !(versionObj instanceof Number)) {
            return ResponseEntity.badRequest().build();
        }

        if (jobPromptVersionRepository.findByJobIdAndAgentName(jobId, agentName).isPresent()) {
            return ResponseEntity.ok().build();
        }

        ai.gitoracle.core.entity.JobPromptVersion row = new ai.gitoracle.core.entity.JobPromptVersion();
        row.setId(UUID.randomUUID());
        row.setJobId(jobId);
        row.setAgentName(agentName);
        row.setPromptVersion(((Number) versionObj).intValue());
        jobPromptVersionRepository.save(row);
        return ResponseEntity.ok().build();
    }

    /**
     * Measured per-prompt-version performance, replacing the dashboard's previously
     * hardcoded Prompt Versions table.
     *
     * accuracy is null (not 0) when a version has no finished jobs yet, and
     * avgTokens is null when nothing recorded token usage — the UI renders those as
     * "—" so an unmeasured version is visibly distinct from one that measured zero.
     */
    @GetMapping("/prompts/stats")
    public ResponseEntity<List<Map<String, Object>>> getPromptStats() {
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (Object[] r : jobPromptVersionRepository.aggregatePerformance()) {
            long jobs = ((Number) r[2]).longValue();
            long successes = ((Number) r[3]).longValue();
            Map<String, Object> row = new HashMap<>();
            row.put("agent", r[0]);
            row.put("version", ((Number) r[1]).intValue());
            row.put("jobs", jobs);
            row.put("successes", successes);
            row.put("accuracy", jobs == 0 ? null : (double) successes / jobs);
            row.put("avgTokens", r[4] == null ? null : ((Number) r[4]).longValue());
            row.put("active", Boolean.TRUE.equals(r[5]));
            out.add(row);
        }
        return ResponseEntity.ok(out);
    }

    @GetMapping("/evals")
    public ResponseEntity<List<EvalRun>> getEvals() {
        List<EvalRun> evals = entityManager.createQuery("SELECT e FROM EvalRun e ORDER BY e.createdAt DESC", EvalRun.class)
            .setMaxResults(50)
            .getResultList();
        return ResponseEntity.ok(evals);
    }
}
