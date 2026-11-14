package ai.gitoracle.orchestrator;

import ai.gitoracle.orchestrator.token.AgentJobRepository;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A mocked AgentJobRepository whose compare-and-set state update succeeds, as it
 * does when nobody else changed the job. A bare mock answers 0 ("someone else
 * moved it"), which makes every JobStateService transition back off.
 */
public final class TestJobRepository {

    private TestJobRepository() {}

    public static AgentJobRepository create() {
        AgentJobRepository repository = mock(AgentJobRepository.class);
        when(repository.moveState(any(), any(), any(), any(), any())).thenReturn(1);
        return repository;
    }
}
