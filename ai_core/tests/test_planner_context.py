"""The planner bases its plan on what the suspect commit actually changed.

It used to see only the commit message, and on the rate-limiter eval case
guessed the off-by-one backwards. These pin that the real diff reaches the
planner's prompt and that the fixer is told which commit it was."""
import asyncio
import os
import subprocess
import sys

import pytest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

import agents.planner.main as planner  # noqa: E402


@pytest.fixture
def repo(tmp_path):
    git = lambda *a: subprocess.run(["git", *a], cwd=tmp_path, check=True, capture_output=True, text=True)  # noqa: E731
    git("init", "-q"); git("config", "user.email", "t@example.com"); git("config", "user.name", "T")
    (tmp_path / "RateLimiter.java").write_text("boolean allow(int n) { return n < limit; }\n")
    git("add", "."); git("commit", "-qm", "base")
    (tmp_path / "RateLimiter.java").write_text("boolean allow(int n) { return n <= limit; }\n")
    git("commit", "-qam", "chore: tidy rate limiter")
    return tmp_path, git("rev-parse", "HEAD").stdout.strip()


@pytest.fixture
def captured(monkeypatch):
    prompts, published = [], []

    class _NoFacts:
        async def retrieve_facts(self, *args, **kwargs):
            return []

    async def llm(messages, **kwargs):
        prompts.append(messages[-1]["content"])
        return planner.PlannerOutput(strategy="surgical_patch", affected_files=["RateLimiter.java"],
                                     affected_functions=["allow"], max_lines_to_change=1,
                                     reasoning="restore <", confidence=0.9)

    async def prompt(agent, default):
        return default, "test-version"

    async def noop(*args, **kwargs):
        return None

    class _Producer:
        async def publish(self, topic, payload):
            published.append((topic, payload))

    monkeypatch.setattr(planner, "SemanticMemory", _NoFacts)
    monkeypatch.setattr(planner, "llm_structured", llm)
    monkeypatch.setattr(planner, "fetch_prompt_versioned", prompt)
    monkeypatch.setattr(planner, "report_prompt_version", noop)
    monkeypatch.setattr(planner, "report_event", noop)
    monkeypatch.setattr(planner, "get_producer", lambda: _Producer())
    return prompts, published


def _investigation(sha):
    return {
        "ranked_causes": [{"commit_sha": sha, "author": "T", "message": "chore: tidy rate limiter",
                           "causal_effect_score": 0.9, "reasoning": "changed the limit check"}],
        "narrative": "The limiter admits one request too many.", "confidence_score": 0.9,
        "affected_files": ["RateLimiter.java"], "recommended_strategy": "surgical_patch",
    }


def test_the_planner_prompt_contains_the_suspect_commits_diff(repo, captured):
    path, sha = repo
    prompts, _ = captured
    request = planner.PlannerRequest(
        tenant_id="00000000-0000-0000-0000-000000000000", repo_path=str(path),
        bug_description="limiter admits too many",
        investigation_result=planner.InvestigationResult(**_investigation(sha)), job_id="job-1")

    asyncio.run(planner.create_plan(request))

    assert "-boolean allow(int n) { return n < limit; }" in prompts[0]
    assert "+boolean allow(int n) { return n <= limit; }" in prompts[0]


def test_the_fixer_is_told_which_commit_introduced_the_bug(repo, captured):
    path, sha = repo
    _, published = captured

    asyncio.run(planner.handle_planner_job({
        "job_id": "job-1", "repo_path": str(path), "repo_url": "https://github.com/o/r",
        "investigation_result": _investigation(sha)}))

    topic, payload = published[-1]
    assert topic == planner.topics.JOB_FIX
    assert payload["root_commit"] == sha
