package ai.gitoracle.orchestrator.repository;

import ai.gitoracle.core.model.postgres.UserAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserAccountRepository extends JpaRepository<UserAccount, UUID> {

    Optional<UserAccount> findByGithubId(Long githubId);
}
