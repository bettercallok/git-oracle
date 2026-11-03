"""
Prometheus metrics for the Python agents, served at GET /metrics.

Prometheus scraped /metrics on all seven agents (infrastructure/prometheus/
prometheus.yml) but no agent served it, so every agent target was permanently
down and nothing about LLM calls — the slowest and most expensive part of the
pipeline — was measured anywhere outside Langfuse. These are the series the
Grafana dashboard reads:

    gitoracle_llm_requests_total{agent,outcome}     ok | schema_error | provider_error | unavailable
    gitoracle_llm_latency_seconds{agent}            histogram, request to parsed reply
    gitoracle_llm_tokens_total{agent,kind}          prompt | completion
    gitoracle_kafka_messages_total{agent,topic,outcome}   handled | failed | dead_lettered
    gitoracle_kafka_consumer_up{agent,topic}        1 while the consumer loop is alive

plus prometheus_client's default process_* and python_* collectors.

/metrics is exempt from the internal token (see internal_auth.EXEMPT_PATHS),
like /health: Prometheus has no token, and these are counts, not data. Agents
bind to 127.0.0.1, so it is not reachable from off the host.
"""
from typing import Optional

from fastapi import FastAPI, Response
from prometheus_client import CONTENT_TYPE_LATEST, Counter, Gauge, Histogram, generate_latest

LLM_REQUESTS = Counter(
    "gitoracle_llm_requests_total", "LLM calls made through llm_structured", ["agent", "outcome"])
LLM_LATENCY = Histogram(
    "gitoracle_llm_latency_seconds", "Time from sending an LLM request to its reply", ["agent"],
    buckets=(0.5, 1, 2, 4, 8, 15, 30, 60, 120))
LLM_TOKENS = Counter(
    "gitoracle_llm_tokens_total", "Tokens used by LLM calls", ["agent", "kind"])
KAFKA_MESSAGES = Counter(
    "gitoracle_kafka_messages_total", "Kafka messages an agent consumed, by result", ["agent", "topic", "outcome"])
KAFKA_CONSUMER_UP = Gauge(
    "gitoracle_kafka_consumer_up", "1 while the agent's Kafka consumer loop is alive", ["agent", "topic"])


def canonical_agent(name: Optional[str]) -> str:
    """'planner_agent' and 'planner' are the same agent (see the orchestrator's AgentNames)."""
    n = (name or "unknown").strip().lower()
    return n[: -len("_agent")] if n.endswith("_agent") else n


LLM_OUTCOMES = ("ok", "schema_error", "provider_error", "unavailable")
KAFKA_OUTCOMES = ("handled", "failed", "dead_lettered")


def preregister_agent(agent: str, topic: Optional[str] = None) -> None:
    """
    Create this agent's series at zero. A series that first appears at 1 hides
    its first event from increase()/rate() — Prometheus never saw the 0 it rose
    from — so an agent's first LLM call or first message would be missing.
    """
    a = canonical_agent(agent)
    for outcome in LLM_OUTCOMES:
        LLM_REQUESTS.labels(agent=a, outcome=outcome)
    for kind in ("prompt", "completion"):
        LLM_TOKENS.labels(agent=a, kind=kind)
    LLM_LATENCY.labels(agent=a)
    if topic is not None:
        for outcome in KAFKA_OUTCOMES:
            KAFKA_MESSAGES.labels(agent=a, topic=topic, outcome=outcome)


def add_metrics_route(app: FastAPI) -> None:
    @app.get("/metrics", include_in_schema=False)
    async def metrics() -> Response:
        return Response(generate_latest(), media_type=CONTENT_TYPE_LATEST)
