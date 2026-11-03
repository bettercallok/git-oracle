"""
GET /metrics on the agents, and what llm_structured records there.

Prometheus scraped /metrics on all seven agents, but none served it: every
agent target was permanently down and nothing about LLM calls was measured.
"""
import asyncio
import os
import sys

import httpx
import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient
from fastapi import Depends
from pydantic import BaseModel, ValidationError
from prometheus_client import REGISTRY

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from shared import structured_output  # noqa: E402
from shared.health import add_health_route  # noqa: E402
from shared.internal_auth import require_internal_token  # noqa: E402

URL = "http://llm.test/v1/chat/completions"


def _value(name: str, **labels) -> float:
    return REGISTRY.get_sample_value(name, labels) or 0.0


def test_metrics_is_served_without_the_internal_token_and_other_routes_still_need_it(monkeypatch):
    monkeypatch.setenv("GITORACLE_INTERNAL_TOKEN", "secret")
    app = FastAPI(dependencies=[Depends(require_internal_token)])
    add_health_route(app, "planner")

    @app.get("/plan")
    async def plan():
        return {}

    client = TestClient(app)
    r = client.get("/metrics")
    assert r.status_code == 200
    assert "text/plain" in r.headers["content-type"]
    assert "gitoracle_llm_requests_total" in r.text
    assert client.get("/plan").status_code == 401


class Answer(BaseModel):
    value: int


def _call(monkeypatch, content: str):
    async def fake_execute(client, base_url, payload, headers=None):
        return httpx.Response(200, json={"choices": [{"message": {"content": content}}],
                                         "usage": {"prompt_tokens": 30, "completion_tokens": 4, "total_tokens": 34}},
                              request=httpx.Request("POST", URL))
    monkeypatch.setattr(structured_output, "_execute_with_rate_limit_backoff", fake_execute)
    monkeypatch.setattr(structured_output, "_lf", None)
    return asyncio.run(structured_output.llm_structured(
        messages=[{"role": "user", "content": "2+2"}], output_schema=Answer, base_url=URL,
        agent_name="metricstest_agent"))


def test_llm_calls_are_counted_by_canonical_agent_and_outcome_with_tokens_and_latency(monkeypatch):
    ok0 = _value("gitoracle_llm_requests_total", agent="metricstest", outcome="ok")
    bad0 = _value("gitoracle_llm_requests_total", agent="metricstest", outcome="schema_error")
    prompt0 = _value("gitoracle_llm_tokens_total", agent="metricstest", kind="prompt")
    lat0 = _value("gitoracle_llm_latency_seconds_count", agent="metricstest")

    assert _call(monkeypatch, '{"value": 4}').value == 4
    with pytest.raises(ValidationError):
        _call(monkeypatch, '{"value": "four"}')

    assert _value("gitoracle_llm_requests_total", agent="metricstest", outcome="ok") == ok0 + 1
    assert _value("gitoracle_llm_requests_total", agent="metricstest", outcome="schema_error") == bad0 + 1
    assert _value("gitoracle_llm_tokens_total", agent="metricstest", kind="prompt") == prompt0 + 60
    assert _value("gitoracle_llm_latency_seconds_count", agent="metricstest") == lat0 + 2


def test_a_provider_refusal_is_counted_as_provider_error(monkeypatch):
    async def refuse(client, base_url, payload, headers=None):
        return httpx.Response(400, json={"error": {"message": "nope", "code": "bad"}},
                              request=httpx.Request("POST", URL))
    monkeypatch.setattr(structured_output, "_execute_with_rate_limit_backoff", refuse)
    before = _value("gitoracle_llm_requests_total", agent="metricstest", outcome="provider_error")
    with pytest.raises(structured_output.LLMRequestError):
        asyncio.run(structured_output.llm_structured(
            messages=[{"role": "user", "content": "x"}], output_schema=Answer, base_url=URL,
            agent_name="metricstest"))
    assert _value("gitoracle_llm_requests_total", agent="metricstest", outcome="provider_error") == before + 1
