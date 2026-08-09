"""
Regression test for _report_tokens() in shared/structured_output.py.

Context: every agent calls _report_tokens() after each LLM call to record usage
against the orchestrator's token budget tracker. It posted to
"{ORCHESTRATOR_URL}/budget/{job_id}/record", but BudgetController is mapped at
/api/v1/budget, not /budget — every single report 404'd, silently, because the
failure inside _report_tokens is only a warning log. Confirmed live:
token_budget_used was 0 across all 29 jobs in the database at the time this was
found, so every token-based figure anywhere in the system (the Job Feed's
"Tokens Used" column, the Eval Dashboard's "Avg Tokens") was structurally always
zero, and nothing in the UI indicated the reports were failing at all.

No pytest-asyncio/respx dependency needed or added: _report_tokens is exercised
via asyncio.run() from a plain sync test, with httpx.AsyncClient.post monkeypatched
to capture the URL it was actually called with rather than hit the network.
"""
import asyncio
import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from shared.structured_output import _report_tokens  # noqa: E402


class _FakeResponse:
    status_code = 200


def test_report_tokens_posts_to_the_versioned_budget_path(monkeypatch):
    captured = {}

    async def fake_post(self, url, json=None, **kwargs):
        captured["url"] = url
        captured["json"] = json
        return _FakeResponse()

    monkeypatch.setattr("httpx.AsyncClient.post", fake_post)

    asyncio.run(_report_tokens(job_id="job-123", tokens_used=500, agent_name="fixer_agent"))

    assert captured["url"] == "http://localhost:8083/api/v1/budget/job-123/record"
    assert captured["json"] == {"tokensUsed": 500, "agentName": "fixer_agent"}


def test_report_tokens_swallows_a_failed_request_rather_than_raising(monkeypatch):
    """The caller (every agent's LLM call) must never fail because token
    reporting failed — that would turn a bookkeeping problem into a broken fix."""
    async def fake_post(self, url, json=None, **kwargs):
        raise ConnectionError("orchestrator unreachable")

    monkeypatch.setattr("httpx.AsyncClient.post", fake_post)

    # Must not raise.
    asyncio.run(_report_tokens(job_id="job-123", tokens_used=500, agent_name="fixer_agent"))
