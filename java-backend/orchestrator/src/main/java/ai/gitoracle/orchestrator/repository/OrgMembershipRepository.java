package ai.gitoracle.orchestrator.repository;

import ai.gitoracle.core.model.postgres.OrgMembership;
import ai.gitoracle.core.model.postgres.OrgRole;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface OrgMembershipRepository extends JpaRepository<OrgMembership, UUID> {

    List<OrgMembership> findByUserId(UUID userId);

    List<OrgMembership> findByTenantIdOrderByCreatedAt(UUID tenantId);

    Optional<OrgMembership> findByTenantIdAndUserId(UUID tenantId, UUID userId);

    /** Locked, so two owners removing each other at once can't leave an organisation with none. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT m FROM OrgMembership m WHERE m.tenantId = :tenantId AND m.role = :role")
    List<OrgMembership> lockByTenantAndRole(@Param("tenantId") UUID tenantId, @Param("role") OrgRole role);
}
