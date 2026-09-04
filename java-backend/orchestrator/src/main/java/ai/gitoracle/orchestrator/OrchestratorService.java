package ai.gitoracle.orchestrator;

import ai.gitoracle.core.kafka.KafkaTopics;
import ai.gitoracle.core.kafka.event.ErrorIngestedEvent;
import ai.gitoracle.core.model.postgres.AgentJob;
import ai.gitoracle.orchestrator.token.AgentJobRepository;
import ai.gitoracle.orchestrator.service.WorkspaceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class OrchestratorService {

    private static final Logger logger = LoggerFactory.getLogger(OrchestratorService.class);
    
    private final AgentJobRepository jobRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final WorkspaceService workspaceService;
    private final EntityManager entityManager;
    private final ai.gitoracle.orchestrator.repository.PrOutcomeRepository prOutcomeRepository;
    private final RestTemplate restTemplate = new RestTemplate();
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();

    // Every internal service now requires this on every request (see
    // InternalAuthFilter on the receiving side) — it used to be reachable
    // with no auth of its own as long as you knew the port.
    @Value("${gitoracle.internal-token:}")
    private String internalToken;

    public OrchestratorService(AgentJobRepository jobRepository, KafkaTemplate<String, Object> kafkaTemplate,
                               WorkspaceService workspaceService, EntityManager entityManager,
                               ai.gitoracle.orchestrator.repository.PrOutcomeRepository prOutcomeRepository) {
        this.jobRepository = jobRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.workspaceService = workspaceService;
        this.entityManager = entityManager;
        this.prOutcomeRepository = prOutcomeRepository;
    }

    /** Wraps a request body with the X-Internal-Token header every internal-service call needs. */
    private <T> HttpEntity<T> withInternalAuth(T body) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Internal-Token", internalToken);
        return new HttpEntity<>(body, headers);
    }

    /**
     * Persists the real-world outcome of a PR GitOracle opened.
     *
     * The "github-pr-events" topic had a consumer in github-bot from the start, but
     * no producer until the Error Ingestor's pull_request handler was added — so no
     * outcome was ever recorded and the dashboard's merge-rate figures were
     * hardcoded. github-bot has no JPA dependency, so persistence lives here, in the
     * service that already owns the database and serves the dashboard API.
     */
    @KafkaListener(topics = KafkaTopics.GITHUB_PR_EVENTS, groupId = "orchestrator-group")
    public void handlePrOutcome(Map<String, String> event) {
        String jobIdStr = event.get("jobId");
        String type = event.get("type");
        if (jobIdStr == null || type == null) {
            logger.warn("PR outcome event missing jobId or type — ignoring: {}", event);
            return;
        }

        // Strip the PR_/REVIEW_ prefixes the GitHub-facing event names carry.
        String outcome = switch (type) {
            case "PR_MERGED"       -> "MERGED";
            case "PR_CLOSED"       -> "CLOSED";
            case "REVIEW_APPROVED" -> "APPROVED";
            case "COMMIT_REVERTED" -> "REVERTED";
            default -> null;
        };
        if (outcome == null) {
            logger.warn("Unknown PR outcome type '{}' — ignoring.", type);
            return;
        }

        final UUID jobId;
        try {
            jobId = UUID.fromString(jobIdStr);
        } catch (IllegalArgumentException e) {
            logger.warn("PR outcome event carried a non-UUID jobId '{}' — ignoring.", jobIdStr);
            return;
        }

        // Kafka redelivers; GitHub also retries webhooks. Recording the same
        // outcome twice would inflate the merge rate, so make this idempotent.
        if (prOutcomeRepository.existsByJobIdAndOutcome(jobId, outcome)) {
            logger.info("PR outcome {} for job {} already recorded — ignoring duplicate.", outcome, jobId);
            return;
        }

        ai.gitoracle.core.entity.PrOutcome row = new ai.gitoracle.core.entity.PrOutcome();
        row.setId(UUID.randomUUID());
        row.setJobId(jobId);
        row.setOutcome(outcome);
        row.setReviewer(event.getOrDefault("reviewer", "unknown"));
        row.setPrUrl(event.getOrDefault("prUrl", ""));
        prOutcomeRepository.save(row);

        logger.info("Recorded PR outcome {} for job {} (by {})", outcome, jobId, row.getReviewer());
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

            // The event's tenant is authoritative — it was derived from the
            // authenticated API key at the gateway. Falling back to the default
            // tenant only covers events published before this field existed.
            job.setTenantId(event.getTenantId() != null
                ? event.getTenantId()
                : ai.gitoracle.core.model.postgres.Tenant.DEFAULT_ID);
            job.setCreatedAt(OffsetDateTime.now());
        }

        job.setState("INVESTIGATING");
        jobRepository.save(job);

        logger.info("Job {} ready. Triggering AI Planner...", job.getId());
        
        // 2. Clone the repository (a specific branch, if the job requested one) into
        // a dynamic workspace
        String branch = event.getBranch();
        String repoPath = workspaceService.cloneRepository(event.getRepoUrl(), branch, job.getId());

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
        // Carried the length of the chain (investigate → plan → fix) so a job that
        // originated from an explicit human request still reaches the Fixer with that
        // request intact. Without this the instruction is silently dropped the moment
        // a job is routed through the agents rather than straight to the Fixer.
        investigatorPayload.put("human_instructions",
            event.getHumanInstructions() != null ? event.getHumanInstructions() : "");
        investigatorPayload.put("target_repo",
            event.getTargetRepo() != null ? event.getTargetRepo() : "");
        investigatorPayload.put("branch", branch != null ? branch : "");

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

                // Promote the Investigator's actual conclusion to the job's root cause.
                //
                // rootCommit was previously set once, in handleErrorIngested, to
                // workspaceService.getHeadCommitSha() — i.e. plain `git rev-parse HEAD`,
                // which has nothing to do with the investigation. The Investigator's real
                // finding was computed, persisted into investigation_result, and then never
                // read by anything. Confirmed live on job fe6a69a8: the Investigator ranked
                // 7f8e49eb at 0.8 confidence (correct — the only commit touching the
                // offending file), while the job recorded bbda67f8, which that same
                // investigation had scored 0.0. The eval harness compares rootCommit against
                // the commit that introduced the bug, so it was measuring HEAD and would only
                // ever be "right" when the bug commit happened to be HEAD.
                //
                // HEAD stays as the fallback, so a job whose investigation yields no ranked
                // cause is no worse off than before.
                rankedCause(investigation).ifPresent(cause -> {
                    job.setRootCommit(cause.sha());
                    job.setCausalScore(cause.score());
                    logger.info("Job {} root cause set from investigation: {} (score {})",
                                jobIdStr, cause.sha(), cause.score());
                });

                jobRepository.save(job);
                logger.info("Persisted investigation result for job {}", jobIdStr);
            });
        } catch (Exception e) {
            logger.warn("Could not persist investigation result: {}", e.getMessage());
        }
    }

    // Package-private (not private) so OrchestratorServiceTest can exercise it
    // directly instead of through reflection.
    record RankedCause(String sha, double score) {}

    /**
     * Highest-scoring entry of the investigation's ranked_causes, if any.
     *
     * Returns empty rather than guessing when the list is missing, empty, or carries
     * no usable commit_sha — callers keep whatever root commit they already had, so a
     * weak investigation never overwrites a known value with a fabricated one.
     */
    @SuppressWarnings("unchecked")
    java.util.Optional<RankedCause> rankedCause(Object investigation) {
        if (!(investigation instanceof Map)) return java.util.Optional.empty();
        Object raw = ((Map<String, Object>) investigation).get("ranked_causes");
        if (!(raw instanceof List)) return java.util.Optional.empty();

        RankedCause best = null;
        for (Object entry : (List<Object>) raw) {
            if (!(entry instanceof Map)) continue;
            Map<String, Object> cause = (Map<String, Object>) entry;
            Object sha = cause.get("commit_sha");
            if (!(sha instanceof String s) || s.isBlank()) continue;
            double score = cause.get("causal_effect_score") instanceof Number n
                ? n.doubleValue() : 0.0;
            if (best == null || score > best.score()) {
                best = new RankedCause(s, score);
            }
        }
        return java.util.Optional.ofNullable(best);
    }

    @KafkaListener(topics = KafkaTopics.FIX_GENERATED, groupId = "orchestrator-group")
    public void handleFixGenerated(Map<String, String> event) {
        String jobIdStr = event.get("jobId");
        logger.info("Orchestrator received FIX_GENERATED event for job: {}", jobIdStr);
        
        // Update state, and persist the patch itself. AgentJob.fixPatch existed but
        // setFixPatch() was never called anywhere, so every job reported fixPatch=null
        // — which silently broke the eval harness, whose LLM judge grades the agent's
        // patch against the golden fix and therefore always scored 0 against an empty
        // string. It also left the dashboard unable to ever show what was changed.
        //
        // authorizedFiles is persisted here too, alongside the patch it gates, purely
        // for audit/observability — the actual enforcement below reads it straight off
        // the event, not this row.
        String authorizedFilesCsv = event.getOrDefault("authorizedFiles", "");
        jobRepository.findById(UUID.fromString(jobIdStr)).ifPresent(job -> {
            job.setState("TESTING");
            String patch = event.get("patch");
            if (patch != null && !patch.isBlank()) {
                job.setFixPatch(patch);
            }
            job.setAuthorizedFiles(authorizedFilesCsv);
            jobRepository.save(job);
        });

        // 3. Call the Guardrails Agent (:9006) to validate the patch
        try {
            logger.info("Calling Guardrails Agent at :9006...");
            var guardrailsRequest = new java.util.HashMap<String, Object>();
            guardrailsRequest.put("diff", event.get("patch"));
            // patch_scanner.scan_patch computes unauthorized = touched_files - allowed_files,
            // so an empty allowed_files list means EVERY touched file is rejected — that's
            // deliberate deny-by-default, not a bug (see the fixer-side comment on why an
            // unresolvable job legitimately produces an empty list here).
            //
            // This used to read "filesModified" — the fixer's own claim about which file
            // ITS patch touched, taken from the same LLM completion that wrote the patch.
            // A patch validated against a list its own author supplied can never disagree
            // with itself, so that check always passed regardless of what the diff actually
            // touched. "authorizedFiles" is resolved by the fixer BEFORE it calls the
            // patch-generating LLM (from the plan, or a regex fallback over the human's own
            // instructions) — the completion that wrote the diff had no way to influence it.
            java.util.List<String> allowedFiles = authorizedFilesCsv.isBlank()
                ? java.util.Collections.emptyList()
                : java.util.Arrays.asList(authorizedFilesCsv.split(","));
            guardrailsRequest.put("allowed_files", allowedFiles);

            @SuppressWarnings("unchecked")
            Map<String, Object> guardrailsResponse = restTemplate.postForObject(
                "http://localhost:9006/validate/patch", withInternalAuth(guardrailsRequest), Map.class);

            // Guardrails' content heuristics are advisory, not a gate — they are
            // substring matches over the patch's added lines, trivially avoided by
            // anyone who knows they exist, and previously they FAILED the job
            // outright (including on unchanged context lines). They are still worth
            // a reviewer's attention, so a passing patch that tripped one says so
            // here rather than the signal being discarded with the response body.
            if (guardrailsResponse != null) {
                Object advisories = guardrailsResponse.get("advisories");
                if (advisories instanceof java.util.List<?> list && !list.isEmpty()) {
                    logger.warn("Guardrails passed job {} with advisories (non-blocking): {} — touched files: {}",
                        jobIdStr, list, guardrailsResponse.get("touched_files"));
                }
            }
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

            // branch flows through the event chain the same way targetRepo does — an
            // override the job carried since it was created, not a DB lookup — so
            // Test Runner clones and tests the branch GitOracle was actually asked to
            // fix rather than always falling back to the repo's default branch.
            String branch = event.getOrDefault("branch", "");

            var testRequest = new java.util.HashMap<String, Object>();
            testRequest.put("jobId",     jobIdStr);
            testRequest.put("repoUrl",   repoUrl[0]);            // for cloning
            testRequest.put("repoPath",  repoUrl[0]);            // fallback slug infer
            testRequest.put("patchDiff", event.get("patch"));
            testRequest.put("branch",    branch);
            testRequest.put("framework", "UNKNOWN");             // let runner auto-detect

            var response = restTemplate.postForObject("http://localhost:8084/test", withInternalAuth(testRequest), Map.class);

            if (response != null && Boolean.TRUE.equals(response.get("allPassed"))) {
                logger.info("Tests passed! Publishing to {}...", KafkaTopics.TESTS_PASSED);
                Map<String, String> testsPassedPayload = new HashMap<>();
                testsPassedPayload.put("jobId",          jobIdStr);
                testsPassedPayload.put("rootCommit",     rootCommit[0] != null ? rootCommit[0] : "unknown");
                testsPassedPayload.put("patch",          event.get("patch"));
                String targetRepo = event.getOrDefault("targetRepo", "");
                testsPassedPayload.put("targetRepo",     targetRepo);
                testsPassedPayload.put("branch",         branch);
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

        // Idempotency guard. Kafka delivers at-least-once, so this listener WILL be
        // invoked more than once for the same job (a consumer-group rebalance after
        // an orchestrator restart is enough to redeliver). Confirmed live on job
        // 7f7f8bd9: PR creation ran three times, the first opened chillcall#6, the
        // retries failed with "! [rejected] ... (non-fast-forward)" because the
        // branch already existed, and the failure branch below then overwrote a
        // genuinely successful job's state with ESCALATED — losing the PR in the UI
        // and opening duplicate PRs on the target repo. If the PR is already open,
        // this event is a redelivery: acknowledge and stop.
        var existing = jobRepository.findById(UUID.fromString(jobIdStr));
        if (existing.isPresent() && existing.get().getPrUrl() != null
                && !existing.get().getPrUrl().isBlank()) {
            logger.info("Job {} already has PR {} — ignoring duplicate TESTS_PASSED delivery.",
                        jobIdStr, existing.get().getPrUrl());
            return;
        }

        existing.ifPresent(job -> {
            repoFullName[0] = job.getRepo().replace("https://github.com/", "").replace(".git", "");
            errorId[0] = job.getErrorId();
        });

        // Allow the event to override the target repo (for dashboard-triggered jobs)
        String targetRepo = event.getOrDefault("targetRepo", "");
        String prRepo = (targetRepo != null && !targetRepo.isBlank()) ? targetRepo : repoFullName[0];

        // 4. Call the GitHub Bot (:8085) — do NOT mark the job PR_OPENED until this
        // actually succeeds. Confirmed live: this previously set state=PR_OPENED
        // unconditionally *before* calling github-bot, and github-bot itself
        // returned HTTP 200 even on failure (an "Error: ..." string body that
        // nothing here checked) — so a job whose PR creation threw an exception
        // (e.g. a clone DNS blip) still showed as PR_OPENED with prUrl=null forever.
        try {
            logger.info("Calling GitHub Bot API at :8085 to open PR on {}...", prRepo);
            Map<String, Object> prRequest = new HashMap<>();
            prRequest.put("jobId", jobIdStr);
            prRequest.put("repoFullName", prRepo);
            prRequest.put("patchDiff", event.get("patch"));
            // Empty means "the repo's actual default branch" downstream in github-bot —
            // without this, github-bot always checked out and PR'd against
            // repo.getDefaultBranch(), regardless of which branch the fix was actually
            // read from and tested against.
            prRequest.put("sourceBranch", event.getOrDefault("branch", ""));
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

            ResponseEntity<Map> response = restTemplate.postForEntity(
                "http://localhost:8085/pull-request", withInternalAuth(prRequest), Map.class);
            Map<String, Object> body = response.getBody();
            boolean success = body != null && Boolean.TRUE.equals(body.get("success"));

            final boolean finalSuccess = success;
            final String prUrl = success && body != null ? (String) body.get("prUrl") : null;
            final String prError = !success && body != null ? (String) body.get("error") : "No response body from GitHub Bot";

            jobRepository.findById(UUID.fromString(jobIdStr)).ifPresent(job -> {
                if (finalSuccess) {
                    job.setState("PR_OPENED");
                    job.setPrUrl(prUrl);
                    jobRepository.save(job);
                    logger.info("Orchestrator pipeline complete for job: {} — PR: {}", jobIdStr, prUrl);
                } else {
                    escalateUnlessAlreadySucceeded(job, prError);
                }
            });
        } catch (Exception e) {
            logger.error("Failed to contact GitHub Bot. Is it running on port 8085?", e);
            jobRepository.findById(UUID.fromString(jobIdStr))
                .ifPresent(job -> escalateUnlessAlreadySucceeded(job, e.getMessage()));
        }
    }

    /**
     * Marks a job ESCALATED because PR creation failed — but never downgrades a job
     * that already reached PR_OPENED with a real PR URL.
     *
     * Without this guard, a duplicate TESTS_PASSED delivery whose PR attempt fails
     * (branch already pushed by the first delivery → "non-fast-forward") wipes out
     * the success recorded by the first delivery. Confirmed live on job 7f7f8bd9,
     * which held a valid PR URL while displaying ESCALATED in the dashboard.
     */
    // Package-private for OrchestratorServiceTest.
    void escalateUnlessAlreadySucceeded(AgentJob job, String reason) {
        if (job.getPrUrl() != null && !job.getPrUrl().isBlank()) {
            logger.warn("PR creation failed for job {} ({}), but it already has PR {} — keeping PR_OPENED.",
                        job.getId(), reason, job.getPrUrl());
            return;
        }
        job.setState("ESCALATED");
        jobRepository.save(job);
        logger.error("GitHub Bot failed to open PR for job {}: {}", job.getId(), reason);
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
            reviewRequest.put("tenant_id",       job.getTenantId().toString());
            reviewRequest.put("repo_path",       "/tmp/gitoracle-workspaces/" + jobIdStr);
            reviewRequest.put("bug_description", job.getErrorId());
            reviewRequest.put("fixer_patch",     "(stored patch not available — review based on comment only)");
            reviewRequest.put("human_comment",   comment);

            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> reviewResponse =
                restTemplate.postForObject("http://localhost:9003/review", withInternalAuth(reviewRequest), java.util.Map.class);

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
