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

    /**
     * Branch to clone, test, and open the PR against. Empty/null means "whatever
     * `git clone` picks with no branch specified" — i.e. the repo's actual default
     * branch. Previously there was no way to target anything else anywhere in the
     * pipeline: WorkspaceService, the Fixer's private checkout, Test Runner's clone,
     * and github-bot's PR base were all hardcoded to the default. Threaded the same
     * way as humanInstructions/targetRepo — through every Kafka hop rather than a
     * DB lookup, since (like targetRepo) it's an override, not part of the job's
     * durable identity.
     */
    private String branch;
}
