"""
What llm_structured sends to Langfuse. The dashboard's trace viewer labels and
groups calls by generation name, so each call must be named after its agent,
and a reply that failed schema validation must be marked as an error rather
than looking like every other call.
"""
import asyncio
import os
import sys

import httpx
import pytest
from pydantic import BaseModel, ValidationError

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from shared import structured_output  # noqa: E402


class Answer(BaseModel):
    value: int


class _FakeTrace:
    def __init__(self, sink, **kwargs):
        self.sink = sink
        sink["trace"] = kwargs

    def generation(self, **kwargs):
        self.sink.setdefault("generations", []).append(kwargs)


class _FakeLangfuse:
    def __init__(self):
        self.sink: dict = {}

    def trace(self, **kwargs):
        return _FakeTrace(self.sink, **kwargs)

    def flush(self):
        pass


def _reply(content: str) -> httpx.Response:
    return httpx.Response(
        200,
        json={"choices": [{"message": {"content": content}}],
              "usage": {"prompt_tokens": 11, "completion_tokens": 3, "total_tokens": 14}},
        request=httpx.Request("POST", "http://llm.test/v1/chat/completions"),
    )


@pytest.fixture
def langfuse(monkeypatch):
    fake = _FakeLangfuse()
    monkeypatch.setattr(structured_output, "_lf", fake)

    async def no_tokens(*args, **kwargs):
        return None
    monkeypatch.setattr(structured_output, "_report_tokens", no_tokens)
    return fake


def _call(monkeypatch, content: str):
    async def fake_execute(client, base_url, payload, headers=None):
        return _reply(content)
    monkeypatch.setattr(structured_output, "_execute_with_rate_limit_backoff", fake_execute)
    return asyncio.run(structured_output.llm_structured(
        messages=[{"role": "system", "content": "be terse"}, {"role": "user", "content": "2+2"}],
        output_schema=Answer,
        job_id="9e09ccf6-1c2b-4f3e-9a7d-0123456789ab",
        agent_name="planner",
    ))


def test_each_call_is_a_generation_named_after_its_agent_on_the_jobs_trace(monkeypatch, langfuse):
    assert _call(monkeypatch, '{"value": 4}').value == 4

    assert langfuse.sink["trace"]["id"] == "9e09ccf6-1c2b-4f3e-9a7d-0123456789ab"
    assert langfuse.sink["trace"]["name"] == "gitoracle-job"
    (gen,) = langfuse.sink["generations"]
    assert gen["name"] == "planner"
    assert gen["metadata"] == {"agent": "planner"}
    assert gen["level"] == "DEFAULT"
    assert gen["usage"] == {"input": 11, "output": 3, "total": 14}
    assert gen["input"][1]["content"] == "2+2"   # the agent's own messages, not the schema-augmented copy


def test_a_reply_that_fails_the_schema_is_traced_as_an_error_and_still_raises(monkeypatch, langfuse):
    with pytest.raises(ValidationError):
        _call(monkeypatch, '{"value": "four"}')

    (gen,) = langfuse.sink["generations"]
    assert gen["level"] == "ERROR"
    assert "did not match Answer" in gen["status_message"]
    assert gen["output"] == '{"value": "four"}'
