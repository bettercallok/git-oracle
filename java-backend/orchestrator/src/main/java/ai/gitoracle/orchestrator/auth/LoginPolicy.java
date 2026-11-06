package ai.gitoracle.orchestrator.auth;

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
 * The operator's lists in the environment: who runs the platform, and who gets an
 * organisation of their own. Anyone else needs an invitation (see LoginService).
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

    public boolean isPlatformAdmin(long githubId) {
        return adminIds.contains(githubId);
    }

    /** On the operator's list, so they get an organisation of their own. */
    public boolean isInvited(String login) {
        return login != null && invitedLogins.contains(login.toLowerCase(Locale.ROOT));
    }

    /** With both lists empty nobody can sign in, so GitHub sign-in can't be anyone's only way in. */
    public boolean admitsAnyone() {
        return !invitedLogins.isEmpty() || !adminIds.isEmpty();
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
