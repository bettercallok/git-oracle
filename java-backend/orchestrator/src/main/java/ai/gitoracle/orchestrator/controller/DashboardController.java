package ai.gitoracle.orchestrator.controller;

import ai.gitoracle.core.model.postgres.AgentJob;
import ai.gitoracle.core.entity.Escalation;
import ai.gitoracle.core.entity.EvalRun;
import ai.gitoracle.core.kafka.KafkaTopics;
import ai.gitoracle.core.kafka.event.ErrorIngestedEvent;
import ai.gitoracle.core.security.RepoRefValidator;
import ai.gitoracle.orchestrator.dto.Requests;
import ai.gitoracle.orchestrator.security.TenantContext;
import jakarta.validation.Valid;
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
        List<AgentJob> jobs = entityManager.createQuery(
                "SELECT j FROM AgentJob j WHERE j.tenantId = :tenantId ORDER BY j.createdAt DESC", AgentJob.class)
            .setParameter("tenantId", TenantContext.requireTenantId())
            .setMaxResults(50)
            .getResultList();
        return ResponseEntity.ok(jobs);
    }

    @GetMapping("/jobs/{id}")
    public ResponseEntity<AgentJob> getJob(@PathVariable UUID id) {
        return findOwnedJob(id)
            .map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/jobs")
    public ResponseEntity<AgentJob> createJob(@Valid @RequestBody Requests.CreateJob request) {
        // CLI hits this endpoint.
        //
        // The id is deliberately NOT set here. AgentJob#id is @GeneratedValue,
        // so assigning one makes Hibernate classify the instance as detached
        // rather than new, and persist() then throws
        // "detached entity passed to persist". This endpoint returned 500 on
        // every call for that reason — a pre-existing bug, unrelated to
        // tenancy, found while verifying this change. Every other creation path
        // (triggerFix, SemanticDedupService) already left the id unset.
        AgentJob job = new AgentJob();
        job.setTenantId(TenantContext.requireTenantId());
        job.setRepo(request.repoUrl());
        job.setState("QUEUED");
        job.setErrorId("manual-trigger");
        entityManager.persist(job);
        return ResponseEntity.ok(job);
    }

    /**
     * Loads a job only if it belongs to the calling tenant.
     *
     * <p>A job owned by another tenant is reported as <b>absent</b>, not
     * forbidden. A 403 would confirm that the requested UUID names a real job
     * somewhere in the installation, which lets a caller enumerate the existence
     * of other tenants' work even though they can never read it. 404 for
     * "yours doesn't exist" and 404 for "it's someone else's" are indistinguishable,
     * which is the point.
     */
    private java.util.Optional<AgentJob> findOwnedJob(UUID id) {
        AgentJob job = entityManager.find(AgentJob.class, id);
        if (job == null) return java.util.Optional.empty();
        if (!TenantContext.requireTenantId().equals(job.getTenantId())) {
            logger.warn("Tenant {} attempted to access job {} owned by tenant {} — reporting as not found.",
                TenantContext.requireTenantId(), id, job.getTenantId());
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(job);
    }

    private boolean ownsEscalation(Escalation escalation) {
        AgentJob job = escalation.getJob();
        // An escalation with no job cannot be attributed to a tenant. Treat it
        // as not owned rather than as universally accessible.
        if (job == null || job.getTenantId() == null) return false;
        return TenantContext.requireTenantId().equals(job.getTenantId());
    }

    /**
     * Dashboard "Regenerate Fix" button — user provides explicit instructions.
     * Directly publishes job.events.fix with human_instructions injected,
     * bypassing the Reviewer Agent (human intent is already explicit).
     */
    @PostMapping("/jobs/{jobId}/feedback")
    public ResponseEntity<Map<String, String>> submitFeedback(
            @PathVariable UUID jobId,
            @Valid @RequestBody Requests.Feedback request) {

        AgentJob job = findOwnedJob(jobId).orElse(null);
        if (job == null) return ResponseEntity.notFound().build();

        // The "instructions is required" check now lives on the record as
        // @NotBlank, so an empty body is rejected before this method runs.
        String instructions = request.instructions();

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
     * POST /api/v1/trigger — "Ask GitOracle to fix" from the FixCommand page.
     *
     * Two routes, selected by the `investigateFirst` flag (default true):
     *
     *   investigateFirst=true  → publishes ERROR_INGESTED, so handleErrorIngested
     *     clones the repo, records HEAD, and runs the real chain:
     *     investigate → plan → fix. Costs ~4.5k tokens and ~25s.
     *
     *   investigateFirst=false → the original shortcut: straight to job.events.fix
     *     with a synthesised plan. ~900 tokens and ~8s.
     *
     * The shortcut is kept deliberately rather than removed. It is genuinely the
     * right choice when a human has already identified the fix, and it stays usable
     * when the token budget is tight — the free-tier ceiling is per-minute, and the
     * full chain costs roughly five times as much per job.
     *
     * The shortcut's cost, though, is that it fabricates its plan: strategy
     * "dashboard_triggered" with an empty affected_files, so the Fixer receives no
     * file context and no investigation ever runs. That is why root-cause data is
     * absent on every job that took this path.
     */
    @PostMapping("/trigger")
    public ResponseEntity<Map<String, String>> triggerFix(@Valid @RequestBody Requests.TriggerFix request) {
        String repoUrl = request.repoUrl();
        String issue   = request.issueDescription();
        String targetRepo = request.targetRepoOrEmpty();
        // Empty = the repo's actual default branch. Threaded through both routes so
        // GitOracle reads, tests, and opens its PR against this branch instead of
        // whichever branch `git clone` picks with none specified.
        String branch = request.branchOrEmpty();
        boolean investigateFirst = request.investigateFirstOrDefault();

        // The "repoUrl and issueDescription are required" check is now @NotBlank
        // on the record. It also rejects a whitespace-only issueDescription,
        // which the old null check let through into the pipeline as a job with
        // no actual instruction.

        // Reject at the pipeline's entry point, not just at the git-invoking call
        // sites downstream — repoUrl/branch eventually reach `git clone` in
        // WorkspaceService, test-runner, and github-bot, all of which enforce the
        // same restriction, but failing fast here gives the caller a clear 400
        // instead of a job that silently never clones.
        try {
            repoUrl = RepoRefValidator.validateRepoUrl(repoUrl);
            branch = RepoRefValidator.validateBranch(branch);
        } catch (RepoRefValidator.InvalidRepoRefException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }

        // The caller's own tenant, resolved from their API key at the gateway.
        // This was hardcoded to the zero UUID, so every job created through the
        // dashboard was filed against the default tenant no matter who created it.
        UUID tenantId = TenantContext.requireTenantId();

        AgentJob job = new AgentJob();
        job.setRepo(repoUrl);
        job.setState("QUEUED");
        job.setErrorId("dashboard-trigger-" + System.currentTimeMillis());
        job.setCreatedAt(OffsetDateTime.now());
        job.setRawPayload(null);
        job.setTenantId(tenantId);
        entityManager.persist(job);
        entityManager.flush(); // ensure ID is assigned

        logger.info("Dashboard trigger: new job {} for repo {} (investigateFirst={})",
                    job.getId(), repoUrl, investigateFirst);

        if (investigateFirst) {
            // Re-enter through the same door the Error Ingestor uses, so the clone,
            // HEAD capture and agent chain all come from one already-tested path
            // instead of being duplicated here. handleErrorIngested reuses this job
            // via jobId rather than creating a second row for the same request.
            ErrorIngestedEvent event = ErrorIngestedEvent.builder()
                .tenantId(tenantId)
                .jobId(job.getId())
                .errorId(job.getErrorId())
                .errorType("dashboard_request")
                .repoUrl(repoUrl)
                // The user's text is the only evidence available for a job with no
                // stack trace, so it serves as both the thing to investigate and the
                // instruction the Fixer must honour.
                .rawPayload(issue)
                .humanInstructions(issue)
                .targetRepo(targetRepo)
                .branch(branch)
                .build();

            kafkaTemplate.send(KafkaTopics.ERROR_INGESTED, event);

            return ResponseEntity.ok(Map.of(
                "status", "INVESTIGATING",
                "mode", "full-pipeline",
                "jobId", job.getId().toString()
            ));
        }

        // Fast path: synthesise a plan and go straight to the Fixer.
        String repoPath = "/tmp/gitoracle-workspaces/" + job.getId();
        Map<String, Object> fixPayload = new HashMap<>();
        fixPayload.put("job_id", job.getId().toString());
        fixPayload.put("repo_url", repoUrl);
        fixPayload.put("repo_path", repoPath);
        fixPayload.put("error_id", job.getErrorId());
        fixPayload.put("human_instructions", issue);
        fixPayload.put("target_repo", targetRepo);
        fixPayload.put("branch", branch);
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
            "mode", "direct-fix",
            "jobId", job.getId().toString()
        ));
    }

    /**
     * Escalations have no tenant column of their own — they are scoped through
     * the job they belong to, which is the real owner of the tenant
     * relationship. Joining rather than denormalising keeps one source of truth
     * for which tenant a piece of work belongs to.
     */
    @GetMapping("/escalations")
    public ResponseEntity<List<Escalation>> getEscalations() {
        List<Escalation> escalations = entityManager.createQuery(
                "SELECT e FROM Escalation e WHERE e.job.tenantId = :tenantId ORDER BY e.createdAt DESC", Escalation.class)
            .setParameter("tenantId", TenantContext.requireTenantId())
            .setMaxResults(50)
            .getResultList();
        return ResponseEntity.ok(escalations);
    }

    @PostMapping("/escalations/{id}/resolve")
    public ResponseEntity<Void> resolveEscalation(@PathVariable UUID id,
                                                  @Valid @RequestBody Requests.ResolveEscalation request) {
        Escalation escalation = entityManager.find(Escalation.class, id);
        if (escalation != null && !ownsEscalation(escalation)) {
            // Same reasoning as findOwnedJob: indistinguishable from "no such
            // escalation". Approving another tenant's escalation would have
            // resumed their pipeline and opened a pull request against their repo.
            logger.warn("Tenant {} attempted to resolve escalation {} it does not own — reporting as not found.",
                TenantContext.requireTenantId(), id);
            return ResponseEntity.notFound().build();
        }
        if (escalation != null) {
            // Constrained to exactly "approve" or "reject" on the record. It
            // used to be any string, with everything that wasn't "approve"
            // falling through to REJECTED — so a typo, or an absent field,
            // silently rejected an escalation a human meant to approve.
            String action = request.action();
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
    public ResponseEntity<EvalRun> saveEval(@Valid @RequestBody Requests.SaveEval request) {
        // Each field used to be read with an `instanceof Number` test that fell
        // back to 0. A submission with a missing or non-numeric accuracy was
        // therefore stored as a completed eval run scoring 0.0 — indistinguishable
        // in the table from a real run that genuinely scored zero, and it drags
        // down any average computed over that table. Malformed input is now
        // rejected instead of being recorded as a fabricated result.
        EvalRun eval = new EvalRun();
        eval.setId(UUID.randomUUID());
        eval.setGoldenDatasetVersion(request.goldenDatasetVersionOrDefault());
        eval.setAccuracy(request.accuracy());
        eval.setAvgLatencyMs(request.avgLatencyMs());
        eval.setCasesTotal(request.casesTotal());

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
        for (Object[] row : prOutcomeRepository.countByDistinctJobOutcome(TenantContext.requireTenantId())) {
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
            @Valid @RequestBody Requests.RecordPromptVersion request) {

        // The old hand-rolled check returned a bare 400 with no body, so an
        // agent sending a malformed payload got no indication of which field
        // was wrong. Constraints on the record produce a field-level problem
        // detail instead.
        String agentName = request.agentName();

        if (jobPromptVersionRepository.findByJobIdAndAgentName(jobId, agentName).isPresent()) {
            return ResponseEntity.ok().build();
        }

        ai.gitoracle.core.entity.JobPromptVersion row = new ai.gitoracle.core.entity.JobPromptVersion();
        row.setId(UUID.randomUUID());
        row.setJobId(jobId);
        row.setAgentName(agentName);
        row.setPromptVersion(request.version());
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
        for (Object[] r : jobPromptVersionRepository.aggregatePerformance(TenantContext.requireTenantId())) {
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
