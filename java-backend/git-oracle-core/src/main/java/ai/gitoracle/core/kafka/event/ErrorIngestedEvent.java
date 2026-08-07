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

    /**
     * Explicit instruction from a human ("add a version field to views.py"), as
     * opposed to rawPayload, which is observed evidence (a stack trace or webhook
     * body). Set when a job originates from the dashboard rather than an incoming
     * error. Threaded all the way to the Fixer, which injects it as a
     * highest-priority constraint on the patch it writes.
     */
    private String humanInstructions;

    /** "owner/repo" override for PR creation, when it differs from repoUrl. */
    private String targetRepo;
}
