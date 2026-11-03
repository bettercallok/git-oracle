package ai.gitoracle.orchestrator.service;

import ai.gitoracle.core.model.postgres.AgentJob;
import ai.gitoracle.orchestrator.repository.JobEventRepository;
import ai.gitoracle.orchestrator.token.AgentJobRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * The orchestrator's Prometheus metrics. The Grafana dashboard queried metrics
 * nothing emitted, so every panel read "No data".
 */
class PipelineMetricsTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final PipelineMetrics metrics = new PipelineMetrics(registry, null);

    private double count(String name, String... tags) {
        var c = registry.find(name).tags(tags).counter();
        return c == null ? -1 : c.count();
    }

    @Test
    void theUsualSeriesExistAtZeroBeforeAnythingHappens() {
        // A counter born at 1 hides its first event from increase()/rate().
        assertThat(count("gitoracle.job.transitions", "from_state", "TESTING", "to_state", "PR_OPENED")).isZero();
        assertThat(count("gitoracle.escalations", "source", "github-bot")).isZero();
        assertThat(count("gitoracle.test.runs", "result", "unverified")).isZero();
        assertThat(count("gitoracle.pr.outcomes", "outcome", "MERGED")).isZero();
    }

    @Test
    void everyStateChangeThroughJobStateServiceIsCounted() {
        JobStateService states = new JobStateService(mock(AgentJobRepository.class), mock(JobEventRepository.class),
                                                     Clock.systemUTC());
        states.setMetrics(metrics);
        AgentJob job = new AgentJob();
        job.setId(UUID.randomUUID());
        job.setState("TESTING");

        states.transition(job, "PR_OPENED", "opened", "github-bot");
        states.transition(job, "PR_OPENED", "same state again: not a change", "github-bot");

        assertThat(count("gitoracle.job.transitions", "from_state", "TESTING", "to_state", "PR_OPENED")).isEqualTo(1);
    }

    @Test
    void pipelineEventsAreRecorded() {
        metrics.escalationQueued("watchdog");
        metrics.testRun("unverified");
        metrics.prOutcome("CLOSED");
        metrics.causalScore(0.86);

        assertThat(count("gitoracle.escalations", "source", "watchdog")).isEqualTo(1);
        assertThat(count("gitoracle.test.runs", "result", "unverified")).isEqualTo(1);
        assertThat(count("gitoracle.pr.outcomes", "outcome", "CLOSED")).isEqualTo(1);
        assertThat(registry.find("gitoracle.causal.score").summary().count()).isEqualTo(1);
    }
}
