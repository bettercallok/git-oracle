package ai.gitoracle.orchestrator.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/** {@code gitoracle.system-status.*}: the services {@link SystemStatusService} checks, name to base URL. */
@ConfigurationProperties(prefix = "gitoracle.system-status")
public class SystemStatusProperties {

    private Map<String, String> javaServices = new LinkedHashMap<>();
    private Map<String, String> agents = new LinkedHashMap<>();

    public Map<String, String> getJavaServices() { return javaServices; }
    public void setJavaServices(Map<String, String> javaServices) { this.javaServices = javaServices; }

    public Map<String, String> getAgents() { return agents; }
    public void setAgents(Map<String, String> agents) { this.agents = agents; }
}
