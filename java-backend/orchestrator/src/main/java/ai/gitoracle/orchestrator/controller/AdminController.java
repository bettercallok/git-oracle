package ai.gitoracle.orchestrator.controller;

import ai.gitoracle.orchestrator.dto.Requests;
import ai.gitoracle.orchestrator.model.TenantConfig;
import ai.gitoracle.orchestrator.security.TenantContext;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Platform administration: create tenants, view a tenant's metrics, mutate
 * per-tenant config, switch active prompt versions.
 *
 * <h2>Authorization (H3)</h2>
 * This controller has no scope check of its own — that is deliberate, not an
 * oversight. Both the api-gateway and, independently, this service's own
 * {@link ai.gitoracle.orchestrator.security.TenantContextFilter} already
 * reject any {@code /api/v1/admin/**} request that does not carry
 * {@code platform:admin} before it ever reaches a method here. Duplicating
 * that check per-endpoint would only be able to drift out of sync with the
 * filter as endpoints are added; the filter covers this controller and every
 * future one under the same prefix uniformly. See that filter's Javadoc for
 * why the check exists twice (gateway + this service) rather than once.
 *
 * <h2>What this endpoint set does NOT do</h2>
 * Tenant creation here is an operator action, not a signup flow — there is no
 * user/session system in front of it (see CLAUDE.md's "Missing entirely"
 * list), and {@link #updateTenantConfig} has never persisted anything (see its
 * own doc). Neither gap is addressed by this authorization fix; both require
 * product work well beyond "who is allowed to call this."
 */
// No @CrossOrigin: reached only through the API Gateway, which already sets
// Access-Control-Allow-Origin globally — a duplicate here breaks CORS entirely
// (see DashboardController for the full explanation).
@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {

    private static final Logger logger = LoggerFactory.getLogger(AdminController.class);

    @PostMapping("/tenants")
    @org.springframework.transaction.annotation.Transactional
    public ResponseEntity<Map<String, String>> registerTenant(@Valid @RequestBody Requests.RegisterTenant request) {
        // The hand-rolled blank check moved onto the record as @NotBlank, which
        // also bounds the length — org_name is a varchar column, and an
        // over-long value was previously a constraint violation surfaced as a 500.
        String tenantName = request.name();

        ai.gitoracle.core.model.postgres.Tenant tenant = new ai.gitoracle.core.model.postgres.Tenant();
        tenant.setOrgName(tenantName);
        entityManager.persist(tenant);

        String tenantId = tenant.getId().toString();
        logger.info("Registered new tenant: {} with ID: {} (requested by tenant {})",
            tenantName, tenantId, TenantContext.tenantId());
        return ResponseEntity.ok(Map.of("tenantId", tenantId, "status", "REGISTERED"));
    }

    /**
     * NOTE: this does not persist anything. It logs the requested values and
     * returns {@code "status":"UPDATED"} regardless of whether any row
     * changed — found while reviewing this controller for H3. It predates
     * this change and is not an authorization gap (the caller genuinely does
     * need platform:admin to reach it), so fixing the response to match
     * reality is left for whoever implements real per-tenant budget/risk
     * config, rather than folded into an authorization-only change.
     */
    @PutMapping("/tenants/{id}/config")
    public ResponseEntity<Map<String, String>> updateTenantConfig(@PathVariable("id") String tenantId,
                                                                  @RequestBody TenantConfig config) {
        logger.info("Updating config for tenant {}: Budget {}, Risk {}",
            tenantId, config.getTokenBudget(), config.getRiskScoreThreshold());
        return ResponseEntity.ok(Map.of("status", "UPDATED", "tenantId", tenantId));
    }

    private final jakarta.persistence.EntityManager entityManager;

    public AdminController(jakarta.persistence.EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @GetMapping("/tenants/{id}/metrics")
    public ResponseEntity<Map<String, Object>> getTenantMetrics(@PathVariable("id") String tenantId) {
        logger.info("Fetching metrics for tenant {} (requested by tenant {})", tenantId, TenantContext.tenantId());

        UUID tenantUuid;
        try {
            tenantUuid = UUID.fromString(tenantId);
        } catch (IllegalArgumentException e) {
            // Was folded into the same catch as the query below and reported
            // as a generic "failed to fetch metrics" with a 200 status — a
            // client typo looked identical to a real backend failure, and
            // neither looked like an error to anything checking the HTTP
            // status code rather than reading the body.
            return ResponseEntity.badRequest().body(Map.of("error", "id is not a valid UUID: " + tenantId));
        }

        try {
            Long jobsCompleted = entityManager.createQuery(
                "SELECT COUNT(j) FROM AgentJob j WHERE j.tenantId = :tenantId AND j.state = 'COMPLETED'", Long.class)
                .setParameter("tenantId", tenantUuid)
                .getSingleResult();

            Long totalJobs = entityManager.createQuery(
                "SELECT COUNT(j) FROM AgentJob j WHERE j.tenantId = :tenantId", Long.class)
                .setParameter("tenantId", tenantUuid)
                .getSingleResult();

            double avgSuccessRate = totalJobs > 0 ? (double) jobsCompleted / totalJobs : 0.0;

            return ResponseEntity.ok(Map.of(
                "tenantId", tenantId,
                "tokensUsed", 45000, // Still hardcoded as token tracking requires external API aggregator in this version
                "jobsCompleted", jobsCompleted,
                "avgSuccessRate", avgSuccessRate
            ));
        } catch (Exception e) {
            // A genuine backend failure now actually looks like one: 500, not
            // a 200 whose body happens to contain the word "error".
            logger.error("Failed to fetch metrics for tenant {}", tenantId, e);
            return ResponseEntity.internalServerError().body(Map.of(
                "tenantId", tenantId,
                "error", "Failed to fetch metrics"
            ));
        }
    }

    @PostMapping("/prompts/{agent}/activate")
    public ResponseEntity<Map<String, String>> switchPromptVersion(@PathVariable("agent") String agent,
                                                                   @Valid @RequestBody Requests.ActivatePrompt request) {
        String version = request.version();
        logger.info("Switching active prompt for agent '{}' to version '{}'", agent, version);
        return ResponseEntity.ok(Map.of("agent", agent, "activeVersion", version));
    }

    @GetMapping("/eval/results")
    public ResponseEntity<Map<String, Object>> getEvalResults() {
        logger.info("Fetching latest LLM eval harness results");
        // Mock eval results
        return ResponseEntity.ok(Map.of(
            "runId", "eval-994",
            "accuracy", 0.98,
            "regressionDetected", false
        ));
    }


}
