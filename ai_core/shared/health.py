"""GET /health for every agent.

Only commit_analyst had one, it returned a static "ok", and it required the
internal token like every other route — so nothing outside GitOracle could
probe it. Worse, "the HTTP server answers" was the only liveness signal anyone
had, and it stayed true while the agent's Kafka consumer — the part that does
the actual work — was dead (the first CI eval run).

This reports the consumer too, and answers 503 when it is down, so a
supervisor, a load balancer or a human can tell a working agent from a zombie.
Exempt from the internal token (see internal_auth.EXEMPT_PATHS): it exposes no
data, only liveness.
"""
import time
from typing import Optional

from fastapi import Depends, FastAPI
from fastapi.responses import JSONResponse

from shared.internal_auth import require_internal_token
from shared.kafka_consumer import KafkaEventConsumer
from shared.metrics import KAFKA_CONSUMER_UP, add_metrics_route, canonical_agent, preregister_agent
from shared.runtime_config import runtime_config


def add_health_route(app: FastAPI, agent: str, consumer: Optional[KafkaEventConsumer] = None) -> None:
    # Every agent calls this, so it is also where /metrics is added (shared/metrics.py).
    add_metrics_route(app)
    preregister_agent(agent, consumer.topic if consumer is not None else None)
    if consumer is not None:
        KAFKA_CONSUMER_UP.labels(agent=canonical_agent(agent), topic=consumer.topic).set_function(lambda: 1 if consumer.alive else 0)

    # Unlike /health this describes the deployment, so it needs the internal token
    # even on an app that doesn't apply it globally.
    @app.get("/runtime-config", dependencies=[Depends(require_internal_token)])
    async def get_runtime_config() -> dict:
        return runtime_config()

    @app.get("/health")
    async def health() -> JSONResponse:
        body: dict = {"status": "ok", "agent": agent}
        if consumer is not None:
            body["consumer"] = {
                "topic": consumer.topic,
                "alive": consumer.alive,
                "restarts": consumer.restarts,
                "seconds_since_last_message": (
                    round(time.time() - consumer.last_message_at)
                    if consumer.last_message_at else None
                ),
            }
            if not consumer.alive:
                body["status"] = "consumer_down"
                return JSONResponse(body, status_code=503)
        return JSONResponse(body)
