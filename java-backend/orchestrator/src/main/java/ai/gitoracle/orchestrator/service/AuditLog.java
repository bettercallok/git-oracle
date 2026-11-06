package ai.gitoracle.orchestrator.service;

import ai.gitoracle.core.model.postgres.AuditEntry;
import ai.gitoracle.orchestrator.repository.AuditEntryRepository;
import ai.gitoracle.orchestrator.security.TenantContext;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Records who changed what. Part of the caller's transaction, so a change and its record land together. */
@Service
public class AuditLog {

    private final AuditEntryRepository entries;

    public AuditLog(AuditEntryRepository entries) {
        this.entries = entries;
    }

    /** The actor is whoever is signed in on this request; none for an API key. */
    public void record(UUID tenantId, String action, String target, String details) {
        record(tenantId, TenantContext.userId(), action, target, details);
    }

    public void record(UUID tenantId, UUID actorUserId, String action, String target, String details) {
        AuditEntry entry = new AuditEntry();
        entry.setTenantId(tenantId);
        entry.setActorUserId(actorUserId);
        entry.setAction(action);
        entry.setTarget(target);
        entry.setDetails(details);
        entry.setCreatedAt(OffsetDateTime.now());
        entries.save(entry);
    }
}
