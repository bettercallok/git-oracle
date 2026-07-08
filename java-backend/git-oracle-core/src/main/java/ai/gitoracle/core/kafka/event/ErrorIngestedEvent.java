package ai.gitoracle.core.kafka.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ErrorIngestedEvent {
    private UUID tenantId;
    private String errorId;
    private String errorType;
    private String repoUrl;
    private String rawPayload;
    private UUID jobId; // Optional: passed if job is immediately created
}
