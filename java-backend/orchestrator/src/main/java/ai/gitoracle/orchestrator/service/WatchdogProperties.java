package ai.gitoracle.orchestrator.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code gitoracle.watchdog.*}: how long a job may sit in each working state
 * before {@link JobWatchdog} escalates it.
 *
 * <p>Limits are generous on purpose — they catch a job nothing is working on
 * any more (an agent died, a message was lost), not a slow LLM. A state with
 * no limit is never escalated; ESCALATED and the terminal states have none.
 */
@ConfigurationProperties(prefix = "gitoracle.watchdog")
public class WatchdogProperties {

    private boolean enabled = true;

    /** How often the watchdog looks. */
    private Duration interval = Duration.ofMinutes(1);

    private Map<String, Duration> limits = new LinkedHashMap<>(Map.of(
        "QUEUED",        Duration.ofMinutes(5),
        "INVESTIGATING", Duration.ofMinutes(10),
        "PLANNING",      Duration.ofMinutes(5),
        // Up to 3 fixer attempts, each with an LLM call and a Maven run in Docker.
        "FIXING",        Duration.ofMinutes(20),
        "TESTING",       Duration.ofMinutes(15),
        "REGENERATING",  Duration.ofMinutes(20)
    ));

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public Duration getInterval() { return interval; }
    public void setInterval(Duration interval) { this.interval = interval; }

    public Map<String, Duration> getLimits() { return limits; }
    public void setLimits(Map<String, Duration> limits) { this.limits = limits; }
}
