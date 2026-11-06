package ai.gitoracle.orchestrator.repository;

import ai.gitoracle.core.model.postgres.GithubConnectRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface GithubConnectRequestRepository extends JpaRepository<GithubConnectRequest, UUID> {

    Optional<GithubConnectRequest> findByStateHash(String stateHash);
}
