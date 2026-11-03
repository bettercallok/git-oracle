"""
Generates ../dashboards/gitOracle-pipeline.json.

The dashboard used to be hand-written JSON whose ~15 queries all named metrics
nothing emitted (gitOracle_state_transition_total, gitOracle_llm_latency_seconds,
...), so every panel read "No data". It is now generated from this file, and
every query names a series that a GitOracle service or exporter actually
serves — see PipelineMetrics.java (orchestrator) and shared/metrics.py (agents).
Edit here and re-run:

    python infrastructure/grafana/scripts/build_dashboard.py
"""
import json
from pathlib import Path

DS = {"type": "prometheus", "uid": "prometheus"}
OUT = Path(__file__).resolve().parent.parent / "dashboards" / "gitOracle-pipeline.json"

panels = []
_id = 0
_y = 0


def _next_id():
    global _id
    _id += 1
    return _id


def row(title):
    global _y
    panels.append({"type": "row", "title": title, "id": _next_id(), "collapsed": False,
                   "gridPos": {"h": 1, "w": 24, "x": 0, "y": _y}, "panels": []})
    _y += 1


def target(expr, legend="", ref="A", instant=False):
    t = {"datasource": DS, "expr": expr, "legendFormat": legend or "__auto", "refId": ref}
    if instant:
        t["instant"] = True
        t["range"] = False
    return t


def stat(title, expr, x, w=4, h=4, unit="short", decimals=None, desc="", thresholds=None, no_value="0"):
    defaults = {"unit": unit, "noValue": no_value, "color": {"mode": "thresholds"},
                "thresholds": {"mode": "absolute", "steps": thresholds or [{"color": "green", "value": None}]}}
    if decimals is not None:
        defaults["decimals"] = decimals
    panels.append({"type": "stat", "title": title, "description": desc, "id": _next_id(), "datasource": DS,
                   "gridPos": {"h": h, "w": w, "x": x, "y": _y},
                   "options": {"reduceOptions": {"calcs": ["lastNotNull"], "fields": "", "values": False},
                               "colorMode": "value", "graphMode": "area", "textMode": "auto", "justifyMode": "auto"},
                   "fieldConfig": {"defaults": defaults, "overrides": []},
                   "targets": [target(expr)]})


def series(title, targets, x, w=12, h=8, unit="short", desc="", stack=False, draw="line"):
    panels.append({"type": "timeseries", "title": title, "description": desc, "id": _next_id(), "datasource": DS,
                   "gridPos": {"h": h, "w": w, "x": x, "y": _y},
                   "options": {"legend": {"displayMode": "list", "placement": "bottom", "showLegend": True},
                               "tooltip": {"mode": "multi", "sort": "desc"}},
                   "fieldConfig": {"defaults": {"unit": unit, "custom": {
                       "drawStyle": draw, "lineWidth": 2, "fillOpacity": 15 if stack else 8, "showPoints": "never",
                       "stacking": {"mode": "normal" if stack else "none", "group": "A"}}}, "overrides": []},
                   "targets": [target(e, l, chr(65 + i)) for i, (e, l) in enumerate(targets)]})


def advance(h):
    global _y
    _y += h


# ── Pipeline ───────────────────────────────────────────────────────────────
row("Pipeline")
RED, AMBER, GREEN = "red", "orange", "green"
stat("Jobs started (24h)", 'sum(increase(gitoracle_job_transitions_total{from_state="QUEUED"}[24h]))', 0,
     decimals=0, desc="Jobs that left QUEUED — into investigation, or straight to the fixer.")
stat("PRs opened (24h)", 'sum(increase(gitoracle_job_transitions_total{to_state="PR_OPENED"}[24h]))', 4, decimals=0)
stat("Fix success rate (24h)",
     '(sum(increase(gitoracle_job_transitions_total{to_state="PR_OPENED"}[24h])) or vector(0))'
     ' / (sum(increase(gitoracle_job_transitions_total{to_state=~"PR_OPENED|FAILED|ESCALATED"}[24h])) > 0)',
     8, unit="percentunit", decimals=0, no_value="—",
     desc="Fix cycles that ended in a PR, out of those that ended (PR opened, failed or escalated).",
     thresholds=[{"color": RED, "value": None}, {"color": AMBER, "value": 0.5}, {"color": GREEN, "value": 0.8}])
stat("Escalations waiting", "max(gitoracle_escalations_pending)", 12, decimals=0,
     desc="Exactly what the dashboard's escalation queue lists.",
     thresholds=[{"color": GREEN, "value": None}, {"color": AMBER, "value": 1}, {"color": RED, "value": 5}])
stat("LLM latency p95 (5m)",
     "histogram_quantile(0.95, sum by (le) (rate(gitoracle_llm_latency_seconds_bucket[5m])))", 16,
     unit="s", decimals=1, no_value="—")
stat("LLM failure rate (1h)",
     '(sum(increase(gitoracle_llm_requests_total{outcome!="ok"}[1h])) or vector(0)) / (sum(increase(gitoracle_llm_requests_total[1h])) > 0)',
     20, unit="percentunit", decimals=0, no_value="—",
     desc="Share of LLM calls that did not return a usable reply. — when there were no calls.",
     thresholds=[{"color": GREEN, "value": None}, {"color": AMBER, "value": 0.1}, {"color": RED, "value": 0.3}])
advance(4)
series("Jobs by state", [("sum by (state) (gitoracle_jobs)", "{{state}}")], 0, stack=True,
       desc="Current jobs in each state, from the database every 30s.")
series("State transitions / min", [("sum by (to_state) (rate(gitoracle_job_transitions_total[5m])) * 60", "→ {{to_state}}")], 12)
advance(8)
series("Escalations queued (per hour)", [
    ("sum by (source) (increase(gitoracle_escalations_total[1h]))", "{{source}}"),
    ("sum by (state) (increase(gitoracle_watchdog_escalations_total[1h]))", "watchdog: stuck in {{state}}"),
], 0, w=8, draw="bars")
series("Test runs (per hour)", [("sum by (result) (increase(gitoracle_test_runs_total[1h]))", "{{result}}")], 8, w=8,
       draw="bars", desc="unverified = the repository had no test suite to run (a safe-pass).")
series("PR outcomes (per day)", [("sum by (outcome) (increase(gitoracle_pr_outcomes_total[1d]))", "{{outcome}}")], 16, w=8,
       draw="bars")
advance(8)
series("Root-cause confidence", [
    ("histogram_quantile(0.5, sum by (le) (rate(gitoracle_causal_score_bucket[6h])))", "median"),
    ("histogram_quantile(0.9, sum by (le) (rate(gitoracle_causal_score_bucket[6h])))", "p90"),
    ("histogram_quantile(0.1, sum by (le) (rate(gitoracle_causal_score_bucket[6h])))", "p10"),
], 0, w=24, h=7, desc="Score of the investigator's top-ranked cause, per investigated job.")
advance(7)

# ── LLM ────────────────────────────────────────────────────────────────────
row("LLM calls")
series("LLM latency p95 by agent", [
    ("histogram_quantile(0.95, sum by (le, agent) (rate(gitoracle_llm_latency_seconds_bucket[5m])))", "{{agent}}")], 0, w=8, unit="s")
series("LLM calls / min by outcome", [
    ("sum by (agent, outcome) (rate(gitoracle_llm_requests_total[5m])) * 60", "{{agent}} · {{outcome}}")], 8, w=8,
    desc="schema_error: the reply didn't fit the schema · provider_error: the provider refused or failed · unavailable: unreachable.")
series("Tokens / min by agent", [
    ("sum by (agent, kind) (rate(gitoracle_llm_tokens_total[5m])) * 60", "{{agent}} · {{kind}}")], 16, w=8, stack=True)
advance(8)

# ── Agents & Kafka ─────────────────────────────────────────────────────────
row("Agents & Kafka")
series("Kafka consumer up", [("max by (agent) (gitoracle_kafka_consumer_up)", "{{agent}}")], 0, w=8,
       desc="1 while the agent's consumer loop is alive. An agent whose HTTP server answers but whose consumer is down does no work.")
series("Messages consumed / min", [
    ("sum by (agent, outcome) (rate(gitoracle_kafka_messages_total[5m])) * 60", "{{agent}} · {{outcome}}")], 8, w=8)
series("Consumer-group lag", [("sum by (consumergroup, topic) (kafka_consumergroup_lag)", "{{consumergroup}} · {{topic}}")], 16, w=8,
       desc="Messages waiting per agent group (kafka-exporter). Growing lag means an agent is falling behind.")
advance(8)

# ── Services ───────────────────────────────────────────────────────────────
row("Services & infrastructure")
series("Scrape targets up", [("up", "{{service}}")], 0, w=8, desc="1 = Prometheus reached the service's metrics endpoint.")
series("HTTP requests / s (Java)", [
    ('sum by (service) (rate(http_server_requests_seconds_count[5m]))', "{{service}}"),
    ('sum by (service) (rate(http_server_requests_seconds_count{status=~"5.."}[5m]))', "{{service}} 5xx"),
], 8, w=8, unit="reqps")
series("JVM heap used", [('sum by (service) (jvm_memory_used_bytes{area="heap"})', "{{service}}")], 16, w=8, unit="bytes",
       desc="Each service's heap is capped by start_local.sh; near the cap means GC pressure.")
advance(8)
series("Agent process memory", [('process_resident_memory_bytes{layer=~"ai-agent|ai-infra"}', "{{service}}")], 0, w=8, unit="bytes")
series("Postgres connections", [("sum by (datname) (pg_stat_database_numbackends)", "{{datname}}")], 8, w=8)
series("Redis memory", [("redis_memory_used_bytes", "used"), ("redis_memory_max_bytes", "max")], 16, w=8, unit="bytes")
advance(8)

dashboard = {
    "__inputs": [],
    "__requires": [{"type": "grafana", "id": "grafana", "name": "Grafana", "version": "10.0.0"},
                   {"type": "datasource", "id": "prometheus", "name": "Prometheus", "version": "1.0.0"}],
    "annotations": {"list": []},
    "description": "GitOracle pipeline, LLM, agent and service health. Generated by infrastructure/grafana/scripts/build_dashboard.py.",
    "editable": True, "fiscalYearStartMonth": 0, "graphTooltip": 1, "id": None, "links": [],
    "panels": panels, "refresh": "30s", "schemaVersion": 38, "tags": ["gitOracle", "ai-agents", "pipeline"],
    "templating": {"list": []}, "time": {"from": "now-6h", "to": "now"}, "timepicker": {}, "timezone": "browser",
    "title": "GitOracle — Pipeline Health", "uid": "gitOracle-pipeline", "version": 2, "weekStart": "",
}
OUT.write_text(json.dumps(dashboard, indent=2) + "\n")
print(f"wrote {OUT} ({len([p for p in panels if p['type'] != 'row'])} panels)")
