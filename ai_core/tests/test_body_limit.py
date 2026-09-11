"""
Regression tests for shared/body_limit.py (L3).

Starlette has no built-in body cap, so a handler's `await request.json()`
buffers whatever arrives. These agents run with no memory ceiling alongside JVMs
deliberately capped at 224-320MB, so one oversized body is a cheap way to push
the host into an OOM kill.
"""
import os
import sys

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from shared.body_limit import BodySizeLimitMiddleware  # noqa: E402


def build_app(limit: int) -> TestClient:
    app = FastAPI()
    app.add_middleware(BodySizeLimitMiddleware, max_body_bytes=limit)

    @app.post("/echo")
    async def echo(payload: dict):
        return {"received": len(str(payload))}

    return TestClient(app)


def test_a_body_under_the_limit_is_delivered():
    client = build_app(1024)

    response = client.post("/echo", json={"k": "v"})

    assert response.status_code == 200


def test_a_body_over_the_limit_is_rejected_with_413():
    client = build_app(256)

    response = client.post("/echo", json={"k": "x" * 2000})

    assert response.status_code == 413
    assert "limit" in response.json()["detail"]


def test_the_handler_never_runs_for_an_oversized_body():
    # The point is not just the status code — it is that the body was never
    # buffered and parsed.
    ran = {"handler": False}
    app = FastAPI()
    app.add_middleware(BodySizeLimitMiddleware, max_body_bytes=128)

    @app.post("/probe")
    async def probe(payload: dict):
        ran["handler"] = True
        return {"ok": True}

    client = TestClient(app)
    response = client.post("/probe", json={"k": "x" * 5000})

    assert response.status_code == 413
    assert ran["handler"] is False


def test_a_body_exactly_at_the_limit_is_allowed():
    # Off-by-one at the boundary would reject legitimate maximum-size requests.
    payload = {"k": "x" * 10}
    import json
    exact = len(json.dumps(payload))
    client = build_app(exact)

    assert client.post("/echo", json=payload).status_code == 200


def test_a_malformed_content_length_is_rejected_rather_than_guessed():
    client = build_app(1024)

    response = client.post(
        "/echo",
        content=b'{"k":"v"}',
        headers={"Content-Length": "not-a-number", "Content-Type": "application/json"},
    )

    assert response.status_code == 400


def test_a_get_request_with_no_body_is_unaffected():
    app = FastAPI()
    app.add_middleware(BodySizeLimitMiddleware, max_body_bytes=16)

    @app.get("/health")
    async def health():
        return {"status": "ok"}

    assert TestClient(app).get("/health").status_code == 200


def test_the_default_limit_is_two_megabytes():
    from shared.body_limit import DEFAULT_MAX_BODY_BYTES

    # Matches the Java services' Tomcat/codec caps; a mismatch would mean the
    # gateway accepts what an agent then refuses.
    assert DEFAULT_MAX_BODY_BYTES == 2 * 1024 * 1024


@pytest.mark.parametrize("limit", [64, 512, 4096])
def test_the_limit_is_configurable(limit):
    client = build_app(limit)

    assert client.post("/echo", json={"k": "x" * (limit * 4)}).status_code == 413
