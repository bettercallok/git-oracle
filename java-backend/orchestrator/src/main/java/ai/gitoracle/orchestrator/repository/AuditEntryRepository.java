package ai.gitoracle.orchestrator.repository;

import ai.gitoracle.core.model.postgres.AuditEntry;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface AuditEntryRepository extends JpaRepository<AuditEntry, UUID> {

    List<AuditEntry> findByTenantIdOrderByCreatedAtDesc(UUID tenantId, Pageable page);
}
