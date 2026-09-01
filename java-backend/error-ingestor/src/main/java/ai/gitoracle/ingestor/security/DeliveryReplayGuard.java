package ai.gitoracle.ingestor.security;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Best-effort, in-process dedup on GitHub's X-GitHub-Delivery ID, bounded to
 * a short recent window. This is NOT a strict security boundary: GitHub's
 * HMAC scheme signs only the raw body with no embedded timestamp/nonce, so
 * a legitimate GitHub redelivery (automatic retry, or an admin clicking
 * "Redeliver" in the GitHub UI) reuses the exact same delivery ID and
 * produces a bit-for-bit identical, equally-valid-signature request — there
 * is no way to distinguish that from an attacker replaying a captured
 * signed payload using the ID and signature alone. What this DOES catch:
 * the same request landing here many times in quick succession (a replayed
 * capture, or a retry storm) doesn't fan out into duplicate jobs/Kafka
 * events each time.
 *
 * Deliberately in-memory rather than Redis-backed: error-ingestor has no
 * Redis dependency today (unlike api-gateway, which already uses it for
 * rate limiting), and adding one is real new infrastructure disproportionate
 * to what this specific gap needs — losing this window on a restart just
 * means briefly reduced protection right after restart, not a correctness
 * bug, unlike the job state itself (which does live in Postgres). A 10-minute
 * window, not 24h: long enough to cover GitHub's own retry behavior, short
 * enough that this stays a small bounded map rather than an unbounded log of
 * every delivery ID ever seen.
 */
@Component
public class DeliveryReplayGuard {

    private static final Duration WINDOW = Duration.ofMinutes(10);

    private final Map<String, Instant> seen = new ConcurrentHashMap<>();

    /**
     * Records this delivery ID and returns true if it was already seen
     * within the window. A null/blank ID (nothing to key on) is never
     * treated as a replay — HMAC verification is still the real gate.
     */
    public boolean isReplay(String deliveryId) {
        if (deliveryId == null || deliveryId.isBlank()) {
            return false;
        }
        Instant now = Instant.now();
        prune(now);
        Instant priorSeenAt = seen.putIfAbsent(deliveryId, now);
        return priorSeenAt != null;
    }

    private void prune(Instant now) {
        seen.entrySet().removeIf(entry -> Duration.between(entry.getValue(), now).compareTo(WINDOW) > 0);
    }
}
