package ai.gitoracle.orchestrator.service;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns a raw Langfuse trace into what the dashboard's trace viewer draws: the
 * job's LLM calls in order, each labelled with the agent that made it, with
 * timing relative to the first call, token counts, and readable messages.
 *
 * <p>Kept free of Spring and HTTP so the mapping can be tested on its own.
 */
public final class TraceViews {

    /** Per text field. A fixer prompt carries whole source files; the browser does not need megabytes of it. */
    static final int MAX_TEXT_CHARS = 60_000;

    private TraceViews() {}

    public record Message(String role, String content, boolean truncated) {}

    public record Call(
        String id,
        String agent,
        String model,
        String startTime,
        String endTime,
        Long offsetMs,
        Long latencyMs,
        long inputTokens,
        long outputTokens,
        long totalTokens,
        Double cost,
        String level,
        String statusMessage,
        List<Message> input,
        String output,
        boolean outputTruncated
    ) {}

    public record Summary(
        int calls,
        long inputTokens,
        long outputTokens,
        long totalTokens,
        long llmTimeMs,
        Long wallClockMs,
        Double totalCost,
        int errors,
        List<String> agents,
        List<String> models
    ) {}

    public record Trace(String id, String startTime, Summary summary, List<Call> calls) {}

    /**
     * Schema titles each agent's calls carry in their system prompt. Traces
     * written before generations were named after their agent all say
     * "llm_structured"; this recovers which agent made them.
     */
    private static final Map<String, String> SCHEMA_TO_AGENT = Map.of(
        "InvestigationResult", "investigator",
        "PlannerOutput", "planner",
        "SearchReplaceEdit", "fixer",
        "ReviewReply", "reviewer",
        "AnalyzeResponse", "commit_analyst");

    public static Trace toTrace(JsonNode trace) {
        List<JsonNode> generations = new ArrayList<>();
        for (JsonNode o : trace.path("observations")) {
            if ("GENERATION".equals(o.path("type").asText("GENERATION"))) generations.add(o);
        }
        generations.sort(Comparator.comparing(o -> o.path("startTime").asText("")));

        Instant first = generations.isEmpty() ? null : instant(generations.get(0).path("startTime").asText(null));
        Instant last = null;
        List<Call> calls = new ArrayList<>();
        Set<String> agents = new LinkedHashSet<>();
        Set<String> models = new LinkedHashSet<>();
        long in = 0, out = 0, total = 0, llmMs = 0;
        double cost = 0;
        boolean anyCost = false;
        int errors = 0;

        for (JsonNode g : generations) {
            Instant start = instant(g.path("startTime").asText(null));
            Instant end = instant(g.path("endTime").asText(null));
            if (end != null && (last == null || end.isAfter(last))) last = end;
            Long latency = start != null && end != null ? Duration.between(start, end).toMillis() : null;
            Long offset = start != null && first != null ? Duration.between(first, start).toMillis() : null;

            JsonNode usage = g.path("usage");
            long gi = usage.path("input").asLong(g.path("promptTokens").asLong(0));
            long go = usage.path("output").asLong(g.path("completionTokens").asLong(0));
            long gt = usage.path("total").asLong(g.path("totalTokens").asLong(gi + go));
            Double gc = g.hasNonNull("calculatedTotalCost") ? g.get("calculatedTotalCost").asDouble() : null;

            String agent = agentOf(g);
            String model = g.path("model").asText(null);
            String level = g.path("level").asText("DEFAULT");
            agents.add(agent);
            if (model != null) models.add(model);
            in += gi; out += go; total += gt;
            if (latency != null) llmMs += latency;
            if (gc != null) { cost += gc; anyCost = true; }
            if ("ERROR".equals(level)) errors++;

            String output = text(g.get("output"));
            calls.add(new Call(
                g.path("id").asText(null), agent, model,
                g.path("startTime").asText(null), g.path("endTime").asText(null),
                offset, latency, gi, go, gt, gc, level,
                g.path("statusMessage").asText(null),
                messages(g.get("input")),
                truncate(output), output != null && output.length() > MAX_TEXT_CHARS));
        }

        Summary summary = new Summary(calls.size(), in, out, total, llmMs,
            first != null && last != null ? Duration.between(first, last).toMillis() : null,
            anyCost ? cost : null, errors, List.copyOf(agents), List.copyOf(models));
        return new Trace(trace.path("id").asText(null),
            generations.isEmpty() ? trace.path("timestamp").asText(null) : generations.get(0).path("startTime").asText(null),
            summary, calls);
    }

    static String agentOf(JsonNode generation) {
        // Canonical: the agents name their calls "planner_agent", "fixer_agent", ...
        String fromMetadata = generation.path("metadata").path("agent").asText("");
        if (!fromMetadata.isBlank()) return AgentNames.canonical(fromMetadata);
        String name = generation.path("name").asText("");
        if (!name.isBlank() && !"llm_structured".equals(name)) return AgentNames.canonical(name);
        // Older traces: recover the agent from the schema title in the system prompt.
        String input = generation.path("input").toString();
        for (var e : SCHEMA_TO_AGENT.entrySet()) {
            if (input.contains("\\\"title\\\": \\\"" + e.getKey() + "\\\"")
                    || input.contains("\"title\": \"" + e.getKey() + "\"")) {
                return e.getValue();
            }
        }
        return "unknown";
    }

    /** Chat messages as role + text; anything else as a single message. */
    static List<Message> messages(JsonNode input) {
        List<Message> out = new ArrayList<>();
        if (input == null || input.isNull()) return out;
        if (input.isArray()) {
            for (JsonNode m : input) {
                if (m.isObject() && m.has("content")) {
                    String content = text(m.get("content"));
                    out.add(new Message(m.path("role").asText("user"), truncate(content),
                        content != null && content.length() > MAX_TEXT_CHARS));
                } else {
                    String content = text(m);
                    out.add(new Message("input", truncate(content), content != null && content.length() > MAX_TEXT_CHARS));
                }
            }
            return out;
        }
        String content = text(input);
        out.add(new Message("input", truncate(content), content != null && content.length() > MAX_TEXT_CHARS));
        return out;
    }

    private static String text(JsonNode node) {
        if (node == null || node.isNull()) return null;
        return node.isTextual() ? node.asText() : node.toString();
    }

    private static String truncate(String s) {
        return s == null || s.length() <= MAX_TEXT_CHARS ? s : s.substring(0, MAX_TEXT_CHARS);
    }

    private static Instant instant(String iso) {
        if (iso == null || iso.isBlank()) return null;
        try {
            return Instant.parse(iso);
        } catch (DateTimeParseException e) {
            try {
                return java.time.OffsetDateTime.parse(iso).toInstant();
            } catch (DateTimeParseException e2) {
                return null;
            }
        }
    }

    /** One row of the trace list: just enough to pick a job. */
    public record ListEntry(int calls, Long latencyMs, Double totalCost, String timestamp) {
        public static ListEntry of(JsonNode trace) {
            JsonNode latency = trace.get("latency");   // seconds, in Langfuse's list view
            return new ListEntry(
                trace.path("observations").size(),
                latency != null && latency.isNumber() ? Math.round(latency.asDouble() * 1000) : null,
                trace.hasNonNull("totalCost") ? trace.get("totalCost").asDouble() : null,
                trace.path("timestamp").asText(null));
        }
    }
}
