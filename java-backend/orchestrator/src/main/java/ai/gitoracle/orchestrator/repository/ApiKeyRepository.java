package ai.gitoracle.orchestrator.repository;

import ai.gitoracle.core.model.postgres.ApiKey;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Key <em>management</em> only. The authentication-time lookup lives in the
 * api-gateway over plain JDBC — see
 * {@code ai.gitoracle.gateway.security.ApiKeyAuthenticator} — because that
 * service is reactive and has no JPA. Minting and revocation are ordinary
 * request-scoped writes and belong here with the rest of the orchestrator's
 * persistence.
 */
@Repository
public interface ApiKeyRepository extends JpaRepository<ApiKey, UUID> {

    Optional<ApiKey> findByKeyPrefix(String keyPrefix);

    List<ApiKey> findByTenantIdOrderByCreatedAtDesc(UUID tenantId);

    long countByRevokedAtIsNull();
}
