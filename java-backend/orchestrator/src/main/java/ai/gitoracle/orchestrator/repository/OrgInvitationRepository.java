package ai.gitoracle.orchestrator.repository;

import ai.gitoracle.core.model.postgres.OrgInvitation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public interface OrgInvitationRepository extends JpaRepository<OrgInvitation, UUID> {

    List<OrgInvitation> findByGithubIdAndAcceptedAtIsNullAndRevokedAtIsNullAndExpiresAtAfter(Long githubId, OffsetDateTime now);

    List<OrgInvitation> findByTenantIdAndAcceptedAtIsNullAndRevokedAtIsNullAndExpiresAtAfterOrderByCreatedAtDesc(UUID tenantId, OffsetDateTime now);

    List<OrgInvitation> findByTenantIdAndGithubIdAndAcceptedAtIsNullAndRevokedAtIsNull(UUID tenantId, Long githubId);
}
