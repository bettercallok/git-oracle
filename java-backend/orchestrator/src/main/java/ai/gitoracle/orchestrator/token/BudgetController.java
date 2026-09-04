package ai.gitoracle.orchestrator.token;

import ai.gitoracle.orchestrator.security.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/**
 * REST API for token budget operations.
 *
 * Python agents call these endpoints after every LLM call
 * to keep the Orchestrator informed of their token consumption.
 */
// No @CrossOrigin: reached only through the API Gateway, which already sets
// Access-Control-Allow-Origin globally — a duplicate here breaks CORS entirely
// (see DashboardController for the full explanation).
@RestController
@RequestMapping("/api/v1/budget")
@RequiredArgsConstructor
@Slf4j
public class BudgetController {

    private final TokenBudgetManager budgetManager;
    private final AgentJobRepository jobRepository;

    /**
     * POST /budget/{jobId}/record
     * Called by Python agents after each LLM call.
     */
    @PostMapping("/{jobId}/record")
    public ResponseEntity<Map<String, Object>> recordUsage(
            @PathVariable UUID jobId,
            @RequestBody RecordUsageRequest request) {

        log.info("Recording {} tokens for job {} from agent {}",
                request.getTokensUsed(), jobId, request.getAgentName());

        int totalUsed = budgetManager.recordUsage(jobId, request.getTokensUsed());

        return ResponseEntity.ok(Map.of(
                "jobId",      jobId.toString(),
                "totalUsed",  totalUsed,
                "recorded",   request.getTokensUsed()
        ));
    }

    /**
     * GET /budget/{jobId}
     * Returns current token budget status for a job.
     *
     * <p>Scoped to the calling tenant. This read had no tenant predicate at
     * all: any caller could retrieve any job's token usage by id, and the
     * 200-vs-404 distinction confirmed whether an arbitrary job id existed
     * anywhere in the installation. Missed when tenant predicates were added
     * to the other read paths — this controller lives under
     * {@code token/} rather than {@code controller/} and was not part of that
     * sweep.
     *
     * <p>Another tenant's job is reported as absent rather than forbidden, for
     * the same reason as everywhere else: a 403 would confirm the id names a
     * real job.
     */
    @GetMapping("/{jobId}")
    public ResponseEntity<Map<String, Object>> getBudgetStatus(@PathVariable UUID jobId) {
        return jobRepository.findById(jobId)
                .filter(job -> TenantContext.requireTenantId().equals(job.getTenantId()))
                .map(job -> {
                    int used    = job.getTokenBudgetUsed();
                    int limit   = job.getTokenBudgetLimit();
                    double pct  = limit > 0 ? (double) used / limit * 100 : 0;

                    return (ResponseEntity<Map<String, Object>>) ResponseEntity.ok(Map.<String, Object>of(
                            "jobId",              jobId.toString(),
                            "tokenBudgetUsed",    used,
                            "tokenBudgetLimit",   limit,
                            "percentUsed",        String.format("%.1f%%", pct),
                            "remaining",          limit - used
                    ));
                })
                .orElse(ResponseEntity.<Map<String, Object>>notFound().build());
    }
}
