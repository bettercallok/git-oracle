package ai.gitoracle.ingestor.service;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;

import java.util.Map;
import java.util.UUID;

import static org.mockito.Mockito.*;

/** A report with no repository has nothing to fix; it used to become an "unknown/repo" job. */
class SemanticDedupServiceTest {

    @Test
    @SuppressWarnings("unchecked")
    void aReportWithoutARepositoryCreatesNoJob() {
        EntityManager em = mock(EntityManager.class);
        KafkaTemplate<String, Object> kafka = mock(KafkaTemplate.class);
        SemanticDedupService service = new SemanticDedupService(em, kafka);

        service.ingest(UUID.randomUUID(), Map.of("error_id", "e1", "stacktrace", "boom"));
        service.ingest(UUID.randomUUID(), Map.of("repo", "  "));
        service.ingest(UUID.randomUUID(), Map.of("repo", 42));

        verifyNoInteractions(em, kafka);
    }
}
