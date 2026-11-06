package ai.gitoracle.orchestrator.auth;

import ai.gitoracle.core.model.postgres.ApiKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Who may sign in, and with what scopes.
 *
 * <p>Sign-in is invite-only because every job spends the operator's LLM budget
 * and runs repository code on their machine.
 *
 * <p>Admins are listed by numeric GitHub id and invitees by login. A released
 * login can be claimed by a stranger: for an invitee that costs a new, empty
 * tenant, but for an admin it would hand over the whole platform.
 */
@Component
public class LoginPolicy {

    private static final Logger logger = LoggerFactory.getLogger(LoginPolicy.class);

    public record Decision(boolean allowed, boolean admin, Set<String> scopes) {}

    private final Set<String> invitedLogins;
    private final Set<Long> adminIds;

    public LoginPolicy(@Value("${gitoracle.auth.invited-github-users:}") String invitedLogins,
                       @Value("${gitoracle.auth.admin-github-ids:}") String adminIds) {
        this.invitedLogins = entries(invitedLogins).stream()
            .map(login -> login.toLowerCase(Locale.ROOT))
            .collect(Collectors.toSet());
        this.adminIds = entries(adminIds).stream()
            .map(LoginPolicy::parseId)
            .filter(id -> id != null)
            .collect(Collectors.toSet());
    }

    public Decision decide(long githubId, String login) {
        if (adminIds.contains(githubId)) {
            return new Decision(true, true,
                Set.of(ApiKey.Scopes.PLATFORM_ADMIN, ApiKey.Scopes.JOBS_READ, ApiKey.Scopes.JOBS_WRITE));
        }
        if (login != null && invitedLogins.contains(login.toLowerCase(Locale.ROOT))) {
            return new Decision(true, false, Set.of(ApiKey.Scopes.JOBS_READ, ApiKey.Scopes.JOBS_WRITE));
        }
        return new Decision(false, false, Set.of());
    }

    private static Set<String> entries(String raw) {
        return Arrays.stream(raw == null ? new String[0] : raw.split(","))
            .map(String::trim)
            .filter(entry -> !entry.isEmpty())
            .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static Long parseId(String entry) {
        try {
            return Long.valueOf(entry);
        } catch (NumberFormatException e) {
            logger.warn("Ignoring GITORACLE_ADMIN_GITHUB_IDS entry '{}': it must be a numeric GitHub id, not a login", entry);
            return null;
        }
    }
}
