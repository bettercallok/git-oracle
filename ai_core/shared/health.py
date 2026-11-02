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

from fastapi import FastAPI
from fastapi.responses import JSONResponse

from shared.kafka_consumer import KafkaEventConsumer


def add_health_route(app: FastAPI, agent: str, consumer: Optional[KafkaEventConsumer] = None) -> None:
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
