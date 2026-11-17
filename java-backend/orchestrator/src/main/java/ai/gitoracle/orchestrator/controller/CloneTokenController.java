package ai.gitoracle.orchestrator.controller;

import ai.gitoracle.core.model.postgres.AgentJob;
import ai.gitoracle.orchestrator.security.TenantContext;
import ai.gitoracle.orchestrator.service.CloneTokens;
import jakarta.persistence.EntityManager;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * POST /api/v1/jobs/{id}/clone-token: a read-only token for the job's own
 * repository, for agents cloning it on another host (shared/clone_auth.py).
 *
 * <p>Internal only: {@code RouteAccess} makes the gateway refuse it, and a
 * request the gateway forwarded is refused here as well. Tokens never ride on
 * Kafka, where every agent logs its payloads and failures are copied to the DLQ.
 * 204 when there is no token to give: clone anonymously.
 */
@RestController
@RequestMapping("/api/v1")
public class CloneTokenController {

    private final EntityManager entityManager;
    private final CloneTokens cloneTokens;

    public CloneTokenController(EntityManager entityManager, CloneTokens cloneTokens) {
        this.entityManager = entityManager;
        this.cloneTokens = cloneTokens;
    }

    @PostMapping("/jobs/{id}/clone-token")
    public ResponseEntity<Map<String, String>> cloneToken(@PathVariable UUID id) {
        if (TenantContext.isAssertedByGateway()) return ResponseEntity.status(403).build();
        AgentJob job = entityManager.find(AgentJob.class, id);
        if (job == null) return ResponseEntity.notFound().build();
        return cloneTokens.forRepository(job.getTenantId(), job.getRepo())
            .map(t -> ResponseEntity.ok(Map.of("token", t.value(), "expires_at", t.expiresAt().toString())))
            .orElseGet(() -> ResponseEntity.noContent().build());
    }
}
