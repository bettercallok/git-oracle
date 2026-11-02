"""
/health must be reachable without the internal token (it's what a supervisor or
probe calls) while every other route still requires it, and must report a dead
Kafka consumer as 503 rather than "ok" — an agent whose consumer has died is not
healthy, even though its HTTP server still answers.
"""
import os
import sys

from fastapi import Depends, FastAPI
from fastapi.testclient import TestClient

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from shared.health import add_health_route  # noqa: E402
from shared.internal_auth import require_internal_token  # noqa: E402
from shared.kafka_consumer import KafkaEventConsumer  # noqa: E402


def _app(consumer=None):
    app = FastAPI(dependencies=[Depends(require_internal_token)])
    add_health_route(app, "fixer", consumer)

    @app.get("/work")
    def work():
        return {"ok": True}

    return TestClient(app)


def test_health_needs_no_token_but_other_routes_still_do(monkeypatch):
    monkeypatch.setenv("GITORACLE_INTERNAL_TOKEN", "secret")
    client = _app()

    assert client.get("/health").status_code == 200
    assert client.get("/work").status_code == 401
    assert client.get("/work", headers={"X-Internal-Token": "secret"}).status_code == 200


def test_a_dead_consumer_makes_the_agent_unhealthy(monkeypatch):
    monkeypatch.setenv("GITORACLE_INTERNAL_TOKEN", "secret")
    consumer = KafkaEventConsumer("job.events.fix", "fixer-agent-group")
    client = _app(consumer)

    consumer.alive = False
    down = client.get("/health")
    assert down.status_code == 503
    assert down.json()["status"] == "consumer_down"

    consumer.alive = True
    up = client.get("/health")
    assert up.status_code == 200
    assert up.json()["consumer"]["topic"] == "job.events.fix"
