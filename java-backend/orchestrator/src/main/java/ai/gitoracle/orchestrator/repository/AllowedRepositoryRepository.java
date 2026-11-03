package ai.gitoracle.orchestrator.repository;

import ai.gitoracle.core.model.postgres.AllowedRepository;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

public interface AllowedRepositoryRepository extends JpaRepository<AllowedRepository, UUID> {

    boolean existsByTenantIdAndRepo(UUID tenantId, String repo);

    List<AllowedRepository> findByTenantIdOrderByRepo(UUID tenantId);

    @Transactional
    long deleteByTenantIdAndRepo(UUID tenantId, String repo);
}
