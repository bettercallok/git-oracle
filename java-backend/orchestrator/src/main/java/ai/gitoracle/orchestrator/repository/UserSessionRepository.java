package ai.gitoracle.orchestrator.repository;

import ai.gitoracle.core.model.postgres.UserSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Session management only. The request-time lookup lives in the api-gateway, over plain JDBC. */
@Repository
public interface UserSessionRepository extends JpaRepository<UserSession, UUID> {

    List<UserSession> findByRevokedAtIsNullAndExpiresAtAfter(OffsetDateTime now);

    void deleteByExpiresAtBefore(OffsetDateTime cutoff);
}
