package ai.gitoracle.orchestrator.controller;

import ai.gitoracle.core.model.postgres.AgentJob;
import ai.gitoracle.orchestrator.security.TenantContext;
import ai.gitoracle.orchestrator.service.LangfuseClient;
import ai.gitoracle.orchestrator.service.TraceViews;
import ai.gitoracle.orchestrator.token.AgentJobRepository;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.EntityManager;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * LLM traces for the dashboard, read from Langfuse on the caller's behalf.
 *
 * <p>Every job's LLM calls go to one Langfuse trace whose id is the job id.
 * A tenant may only read traces of its own jobs: the trace is looked up only
 * after the job is found under the caller's tenant, exactly like every other
 * job route (another tenant's job reads as absent, not forbidden).
 *
 * <p>Langfuse being unconfigured or down is reported in the body
 * ({@code status}) rather than as an HTTP error, so the dashboard can say which
 * of the two it is instead of showing a generic failure.
 */
@RestController
@RequestMapping("/api/v1")
public class TraceController {

    public enum Status { OK, NO_TRACE, NOT_CONFIGURED, UNAVAILABLE }

    private final LangfuseClient langfuse;
    private final AgentJobRepository jobs;
    private final EntityManager entityManager;

    public TraceController(LangfuseClient langfuse, AgentJobRepository jobs, EntityManager entityManager) {
        this.langfuse = langfuse;
        this.jobs = jobs;
        this.entityManager = entityManager;
    }

    @GetMapping("/jobs/{id}/trace")
    public ResponseEntity<Map<String, Object>> jobTrace(@PathVariable UUID id) {
        Optional<AgentJob> job = jobs.findById(id)
            .filter(j -> TenantContext.requireTenantId().equals(j.getTenantId()));
        if (job.isEmpty()) return ResponseEntity.notFound().build();

        Map<String, Object> body = new HashMap<>();
        body.put("jobId", id.toString());
        if (!langfuse.isConfigured()) {
            return ResponseEntity.ok(status(body, Status.NOT_CONFIGURED,
                "Langfuse keys are not set (LANGFUSE_PUBLIC_KEY / LANGFUSE_SECRET_KEY), so no LLM calls were traced."));
        }
        try {
            Optional<JsonNode> raw = langfuse.getTrace(id.toString());
            if (raw.isEmpty()) {
                return ResponseEntity.ok(status(body, Status.NO_TRACE,
                    "No LLM calls have been traced for this job yet."));
            }
            body.put("trace", TraceViews.toTrace(raw.get()));
            return ResponseEntity.ok(status(body, Status.OK, null));
        } catch (LangfuseClient.LangfuseUnavailableException e) {
            return ResponseEntity.ok(status(body, Status.UNAVAILABLE, e.getMessage()));
        }
    }

    /** The caller's recent jobs, each with a summary of its trace when Langfuse has one. */
    @GetMapping("/traces")
    public ResponseEntity<Map<String, Object>> recent(@RequestParam(defaultValue = "50") int limit) {
        List<AgentJob> recentJobs = entityManager.createQuery(
                "SELECT j FROM AgentJob j WHERE j.tenantId = :tenantId ORDER BY j.createdAt DESC", AgentJob.class)
            .setParameter("tenantId", TenantContext.requireTenantId())
            .setMaxResults(Math.max(1, Math.min(limit, 100)))
            .getResultList();

        Map<String, Object> body = new HashMap<>();
        Map<String, JsonNode> traces = Map.of();
        if (!langfuse.isConfigured()) {
            status(body, Status.NOT_CONFIGURED,
                "Langfuse keys are not set (LANGFUSE_PUBLIC_KEY / LANGFUSE_SECRET_KEY), so no LLM calls were traced.");
        } else {
            try {
                traces = langfuse.recentTraces(100);
                status(body, Status.OK, null);
            } catch (LangfuseClient.LangfuseUnavailableException e) {
                status(body, Status.UNAVAILABLE, e.getMessage());
            }
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        for (AgentJob j : recentJobs) {
            Map<String, Object> row = new HashMap<>();
            row.put("jobId", j.getId().toString());
            row.put("repo", j.getRepo());
            row.put("state", j.getState());
            row.put("createdAt", j.getCreatedAt() == null ? null : j.getCreatedAt().toString());
            row.put("tokenBudgetUsed", j.getTokenBudgetUsed());
            JsonNode t = traces.get(j.getId().toString());
            row.put("trace", t == null ? null : TraceViews.ListEntry.of(t));
            rows.add(row);
        }
        body.put("jobs", rows);
        return ResponseEntity.ok(body);
    }

    private static Map<String, Object> status(Map<String, Object> body, Status status, String message) {
        body.put("status", status.name());
        if (message != null) body.put("message", message);
        return body;
    }
}
