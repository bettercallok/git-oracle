package ai.gitoracle.orchestrator.service;

import java.util.Locale;

/**
 * One spelling per agent. The Python agents report token usage and name their
 * LLM calls as "planner_agent", "fixer_agent", ..., but record prompt versions
 * as "planner", "fixer", ... — so anything joining the two needs the same key.
 */
public final class AgentNames {

    private AgentNames() {}

    public static String canonical(String name) {
        if (name == null) return null;
        String n = name.trim().toLowerCase(Locale.ROOT);
        return n.endsWith("_agent") ? n.substring(0, n.length() - "_agent".length()) : n;
    }
}
