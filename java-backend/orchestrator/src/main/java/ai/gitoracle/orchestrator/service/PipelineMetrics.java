package ai.gitoracle.orchestrator.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The pipeline's own Prometheus metrics, served at /actuator/prometheus.
 *
 * <p>The Grafana dashboard ("GitOracle — Pipeline Health") was built against
 * metrics nothing ever emitted — gitOracle_state_transition_total,
 * gitOracle_escalation_total, gitOracle_causal_score, ... — so every panel
 * read "No data". These are the metrics that dashboard now queries, recorded
 * where each event actually happens:
 *
 * <pre>
 *   gitoracle_job_transitions_total{from_state,to_state}   JobStateService, every state change
 *   gitoracle_jobs{state}                                   current jobs per state (refreshed every 30s)
 *   gitoracle_escalations_pending                           what the escalation queue lists
 *   gitoracle_escalations_total{source}                     EscalationService, rows queued
 *   gitoracle_test_runs_total{result}                       passed | failed | unverified | error
 *   gitoracle_pr_outcomes_total{outcome}                    MERGED | CLOSED | APPROVED | REVERTED
 *   gitoracle_causal_score (histogram)                      investigator's top-ranked cause score
 *   gitoracle_watchdog_escalations_total{state}             JobWatchdog (pre-existing)
 * </pre>
 *
 * Counters are installation-wide, not per tenant: Prometheus is an operator
 * tool on loopback, and a tenant label would multiply every series by the
 * number of tenants for no operational benefit.
 */
@Component
public class PipelineMetrics {

    private static final Logger logger = LoggerFactory.getLogger(PipelineMetrics.class);

    private final MeterRegistry registry;
    private final EntityManager entityManager;
    private final MultiGauge jobsByState;
    private final AtomicLong pendingEscalations = new AtomicLong();
    private final DistributionSummary causalScore;

    @Autowired
    public PipelineMetrics(MeterRegistry registry, EntityManager entityManager) {
        this.registry = registry;
        this.entityManager = entityManager;
        this.jobsByState = MultiGauge.builder("gitoracle.jobs")
            .description("Jobs currently in each state")
            .register(registry);
        registry.gauge("gitoracle.escalations.pending", pendingEscalations);
        this.causalScore = DistributionSummary.builder("gitoracle.causal.score")
            .description("Score of the investigator's top-ranked root cause")
            .serviceLevelObjectives(0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.7, 0.8, 0.9, 1.0)
            .register(registry);
        preRegister();
    }

    /**
     * The usual series, created at zero up front. A counter that first appears
     * at 1 hides its first event from increase()/rate() — Prometheus never saw
     * the 0 it rose from — so the first escalation, test run or PR of a fresh
     * orchestrator would otherwise be missing from every dashboard panel.
     */
    private void preRegister() {
        String[][] lifecycle = {
            {"QUEUED", "INVESTIGATING"}, {"QUEUED", "FIXING"}, {"INVESTIGATING", "PLANNING"},
            {"PLANNING", "FIXING"}, {"FIXING", "TESTING"}, {"TESTING", "PR_OPENED"}, {"TESTING", "FAILED"},
            {"INVESTIGATING", "ESCALATED"}, {"PLANNING", "ESCALATED"}, {"FIXING", "ESCALATED"},
            {"TESTING", "ESCALATED"}, {"ESCALATED", "PLANNING"}, {"ESCALATED", "FAILED"},
            {"PR_OPENED", "PR_MERGED"}, {"PR_OPENED", "PR_CLOSED"}, {"PR_OPENED", "REGENERATING"},
            {"REGENERATING", "FIXING"},
        };
        for (String[] t : lifecycle) {
            Counter.builder("gitoracle.job.transitions").description("Job state changes")
                .tags("from_state", t[0], "to_state", t[1]).register(registry);
        }
        for (String source : new String[]{"fixer", "investigator", "planner", "watchdog", "github-bot", "orchestrator"}) {
            Counter.builder("gitoracle.escalations").description("Escalations queued for a human")
                .tag("source", source).register(registry);
        }
        for (String result : new String[]{"passed", "failed", "unverified", "error"}) {
            Counter.builder("gitoracle.test.runs").description("Test-runner verdicts on generated patches")
                .tag("result", result).register(registry);
        }
        for (String outcome : new String[]{"MERGED", "CLOSED", "APPROVED", "REVERTED"}) {
            Counter.builder("gitoracle.pr.outcomes").description("What humans did with GitOracle's pull requests")
                .tag("outcome", outcome).register(registry);
        }
    }

    /** For tests and for code constructed outside Spring: records into a throwaway registry. */
    public static PipelineMetrics noop() {
        return new PipelineMetrics(new SimpleMeterRegistry(), null);
    }

    public void transition(String from, String to) {
        Counter.builder("gitoracle.job.transitions")
            .description("Job state changes")
            .tags("from_state", from == null ? "NONE" : from, "to_state", to)
            .register(registry).increment();
    }

    public void escalationQueued(String source) {
        Counter.builder("gitoracle.escalations")
            .description("Escalations queued for a human")
            .tag("source", source == null ? "unknown" : source)
            .register(registry).increment();
    }

    /** {@code result}: passed, failed, unverified (no test suite ran) or error (runner unreachable). */
    public void testRun(String result) {
        Counter.builder("gitoracle.test.runs")
            .description("Test-runner verdicts on generated patches")
            .tag("result", result)
            .register(registry).increment();
    }

    public void prOutcome(String outcome) {
        Counter.builder("gitoracle.pr.outcomes")
            .description("What humans did with GitOracle's pull requests")
            .tag("outcome", outcome)
            .register(registry).increment();
    }

    public void causalScore(double score) {
        causalScore.record(score);
    }

    /**
     * Jobs per state and pending escalations, from the database. Gauges rather
     * than counters because these are "how many right now", which no counter
     * can answer after a restart. Every 30s; one GROUP BY and one COUNT.
     */
    @Scheduled(initialDelayString = "PT10S", fixedDelayString = "${gitoracle.metrics.refresh-interval:PT30S}")
    @Transactional(readOnly = true)
    public void refreshGauges() {
        if (entityManager == null) return;
        try {
            List<MultiGauge.Row<?>> rows = new ArrayList<>();
            for (Object[] r : entityManager.createQuery(
                    "SELECT j.state, COUNT(j) FROM AgentJob j GROUP BY j.state", Object[].class).getResultList()) {
                rows.add(MultiGauge.Row.of(Tags.of("state", String.valueOf(r[0])), ((Number) r[1]).doubleValue()));
            }
            jobsByState.register(rows, true);
            Long pending = entityManager.createQuery(
                    "SELECT COUNT(e) FROM Escalation e WHERE e.status = 'PENDING' AND e.job.state = 'ESCALATED'", Long.class)
                .getSingleResult();
            pendingEscalations.set(pending == null ? 0 : pending);
        } catch (RuntimeException e) {
            logger.warn("Could not refresh job gauges: {}", e.getMessage());
        }
    }
}
