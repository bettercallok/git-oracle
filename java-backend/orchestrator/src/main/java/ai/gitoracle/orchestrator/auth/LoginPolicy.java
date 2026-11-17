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
 * <p>Admins are listed by numeric GitHub id. Invitees may be listed by id or by
 * login; an id is matched as an id, anything else as a login. Prefer ids: a
 * released login can be claimed by a stranger, who would then get an
 * organisation of their own.
 */
@Component
public class LoginPolicy {

    private static final Logger logger = LoggerFactory.getLogger(LoginPolicy.class);

    private final Set<String> invitedLogins;
    private final Set<Long> invitedIds;
    private final Set<Long> adminIds;

    public LoginPolicy(@Value("${gitoracle.auth.invited-github-users:}") String invitedLogins,
                       @Value("${gitoracle.auth.admin-github-ids:}") String adminIds) {
        Set<String> invited = entries(invitedLogins);
        this.invitedIds = invited.stream()
            .filter(entry -> entry.chars().allMatch(Character::isDigit))
            .map(Long::valueOf)
            .collect(Collectors.toSet());
        this.invitedLogins = invited.stream()
            .filter(entry -> !entry.chars().allMatch(Character::isDigit))
            .map(login -> login.toLowerCase(Locale.ROOT))
            .collect(Collectors.toSet());
        if (!this.invitedLogins.isEmpty()) {
            logger.warn("GITORACLE_INVITED_GITHUB_USERS lists {} login(s). A login someone gives up can be "
                + "registered by anyone; list numeric GitHub ids instead.", this.invitedLogins.size());
        }
        this.adminIds = entries(adminIds).stream()
            .map(LoginPolicy::parseId)
            .filter(id -> id != null)
            .collect(Collectors.toSet());
    }

    public boolean isPlatformAdmin(long githubId) {
        return adminIds.contains(githubId);
    }

    /** On the operator's list, by id or by login, so they get an organisation of their own. */
    public boolean isInvited(long githubId, String login) {
        return invitedIds.contains(githubId)
            || (login != null && invitedLogins.contains(login.toLowerCase(Locale.ROOT)));
    }

    /** With both lists empty nobody can sign in, so GitHub sign-in can't be anyone's only way in. */
    public boolean admitsAnyone() {
        return !invitedLogins.isEmpty() || !invitedIds.isEmpty() || !adminIds.isEmpty();
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
