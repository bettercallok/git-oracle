"""
report_event() writes agent progress into a job's timeline on the orchestrator.

Same approach as test_report_tokens_url.py: run the coroutine with asyncio.run()
and monkeypatch httpx.AsyncClient.post, so no network and no pytest-asyncio.
"""
import asyncio
import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from shared.job_events import ERROR, report_event  # noqa: E402


class _FakeResponse:
    def __init__(self, status_code=202):
        self.status_code = status_code


def _capture(monkeypatch, status=202):
    calls = []

    async def fake_post(self, url, json=None, headers=None, **kwargs):
        calls.append({"url": url, "json": json, "headers": headers})
        return _FakeResponse(status)

    monkeypatch.setattr("httpx.AsyncClient.post", fake_post)
    return calls


def test_posts_to_the_jobs_events_route_with_the_internal_token(monkeypatch):
    monkeypatch.setenv("GITORACLE_INTERNAL_TOKEN", "tok")
    calls = _capture(monkeypatch)

    asyncio.run(report_event("job-123", "fixer", "Attempt 2 of 3 started", details={"attempt": 2}))

    assert calls[0]["url"] == "http://localhost:8083/api/v1/jobs/job-123/events"
    assert calls[0]["json"] == {"type": "AGENT_PROGRESS", "actor": "fixer",
                                "message": "Attempt 2 of 3 started", "details": {"attempt": 2}}
    assert calls[0]["headers"] == {"X-Internal-Token": "tok"}


def test_error_type_is_passed_through(monkeypatch):
    calls = _capture(monkeypatch)

    asyncio.run(report_event("job-123", "fixer", "LLM call failed", type=ERROR))

    assert calls[0]["json"]["type"] == "ERROR"


def test_placeholder_job_ids_are_not_reported(monkeypatch):
    """Direct HTTP calls to an agent use ids like "debug-job"; there is no
    timeline to write to, and posting would just 404 on every call."""
    calls = _capture(monkeypatch)

    for job_id in (None, "", "unknown", "unknown-job", "debug-job"):
        asyncio.run(report_event(job_id, "fixer", "x"))

    assert calls == []


def test_long_messages_are_capped_to_what_the_endpoint_accepts(monkeypatch):
    calls = _capture(monkeypatch)

    asyncio.run(report_event("job-123", "fixer", "x" * 10_000))

    assert len(calls[0]["json"]["message"]) == 4000


def test_a_failed_request_never_raises(monkeypatch):
    """A timeline note is never worth breaking the fix it describes."""
    async def fake_post(self, url, json=None, **kwargs):
        raise ConnectionError("orchestrator unreachable")

    monkeypatch.setattr("httpx.AsyncClient.post", fake_post)

    asyncio.run(report_event("job-123", "fixer", "Attempt 1 of 3 started"))
