"""
Regression tests for _execute_with_rate_limit_backoff() in shared/structured_output.py.

Context: a 429 from the LLM provider used to come back like any other 4xx and be
raised by the caller, so a free-tier per-minute limit failed the agent outright
and escalated the job. Confirmed live on job 9e09ccf6: concurrent pipeline runs
tripped Groq's limit, and every planner and fixer call in the next minute failed
within a second without ever waiting.

Same style as test_report_tokens_url.py: asyncio.run() from plain sync tests,
httpx.AsyncClient.post monkeypatched, and asyncio.sleep recorded instead of slept.
"""
import asyncio
import os
import sys

import httpx
import pytest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from shared import structured_output  # noqa: E402
from shared.structured_output import _execute_with_rate_limit_backoff  # noqa: E402

URL = "https://llm.example/v1/chat/completions"


def _response(status, headers=None):
    return httpx.Response(status, headers=headers or {}, json={},
                          request=httpx.Request("POST", URL))


def _run(monkeypatch, responses):
    """Serve `responses` in order; return (final response or exception, sleeps, call count)."""
    queue = list(responses)
    calls = []
    sleeps = []

    async def fake_post(self, url, json=None, headers=None, **kwargs):
        calls.append(url)
        return queue.pop(0)

    async def fake_sleep(seconds):
        sleeps.append(seconds)

    monkeypatch.setattr("httpx.AsyncClient.post", fake_post)
    monkeypatch.setattr(structured_output.asyncio, "sleep", fake_sleep)

    async def go():
        async with httpx.AsyncClient() as client:
            return await _execute_with_rate_limit_backoff(client, URL, {})

    try:
        result = asyncio.run(go())
    except httpx.HTTPStatusError as e:
        result = e
    return result, sleeps, len(calls)


def test_waits_out_a_429_and_returns_the_eventual_success(monkeypatch):
    result, sleeps, calls = _run(monkeypatch, [_response(429), _response(429), _response(200)])

    assert result.status_code == 200
    assert calls == 3
    assert len(sleeps) == 2


def test_honours_a_numeric_retry_after(monkeypatch):
    result, sleeps, _ = _run(monkeypatch, [_response(429, {"retry-after": "7"}), _response(200)])

    assert result.status_code == 200
    assert sleeps == [7.0]


def test_caps_a_huge_retry_after(monkeypatch):
    monkeypatch.setattr(structured_output, "LLM_RATE_LIMIT_MAX_WAIT_SECONDS", 60.0)
    _, sleeps, _ = _run(monkeypatch, [_response(429, {"retry-after": "3600"}), _response(200)])

    assert sleeps == [60.0]


def test_raises_once_retries_are_exhausted_so_the_fallback_can_run(monkeypatch):
    monkeypatch.setattr(structured_output, "LLM_RATE_LIMIT_RETRIES", 2)
    result, sleeps, calls = _run(monkeypatch, [_response(429)] * 3)

    assert isinstance(result, httpx.HTTPStatusError)
    assert result.response.status_code == 429
    assert calls == 3          # first try + 2 retries
    assert len(sleeps) == 2    # no pointless sleep after the last attempt


@pytest.mark.parametrize("status", [200, 400, 401])
def test_does_not_retry_anything_but_429(monkeypatch, status):
    result, sleeps, calls = _run(monkeypatch, [_response(status)])

    assert result.status_code == status
    assert calls == 1
    assert sleeps == []
