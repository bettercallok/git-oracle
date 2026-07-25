package ai.gitoracle.orchestrator.controller;

import ai.gitoracle.core.model.postgres.AgentJob;
import ai.gitoracle.core.entity.Escalation;
import ai.gitoracle.core.entity.EvalRun;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
@CrossOrigin(origins = "*") // Allow dashboard to access
@Transactional
public class DashboardController {

    private final EntityManager entityManager;

    public DashboardController(EntityManager entityManager) {
        this.entityManager = entityManager;
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
            escalation.setStatus(request.get("resolution"));
            entityManager.merge(escalation);
        }
        return ResponseEntity.ok().build();
    }

    @GetMapping("/evals")
    public ResponseEntity<List<EvalRun>> getEvals() {
        List<EvalRun> evals = entityManager.createQuery("SELECT e FROM EvalRun e ORDER BY e.createdAt DESC", EvalRun.class)
            .setMaxResults(50)
            .getResultList();
        return ResponseEntity.ok(evals);
    }
}
