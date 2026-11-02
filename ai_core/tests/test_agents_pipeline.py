"""
investigate -> plan -> fix through the agents' real HTTP endpoints, in-process.

Until now no test exercised what the agents actually do: the suite covered
helpers (path safety, auth, diff parsing). This runs the three agents' endpoints
in order against a throwaway git repo shaped like eval case1 (null guard removed
from UserService.processPayment), with everything real except the LLM call
itself: shared/structured_output.llm_structured builds its request, appends the
schema and parses the reply as normal, and only the HTTP POST to LLM_BASE_URL is
answered — by the same logic as tests/mock_llm_server.py. Other outbound calls
(orchestrator, prompt registry, memory) fail fast, as they do when those
services are down, which the agents already tolerate.
"""
import importlib.util
import os
import subprocess
import sys
from pathlib import Path

import httpx
import pytest

AI_CORE = Path(__file__).resolve().parent.parent
REPO_ROOT = AI_CORE.parent
LLM_URL = "http://mock-llm.invalid/v1/chat/completions"
TOKEN = "test-internal-token"

# Module-level constants in structured_output read these at import time.
os.environ["LLM_BASE_URL"] = LLM_URL
os.environ["LLM_MODEL_NAME"] = "mock"
os.environ["LANGFUSE_PUBLIC_KEY"] = ""
os.environ["LANGFUSE_SECRET_KEY"] = ""
os.environ.pop("LLM_FALLBACK_BASE_URL", None)
sys.path.insert(0, str(AI_CORE))


def _load(path: Path, name: str):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


mock = _load(AI_CORE / "tests" / "mock_llm_server.py", "mock_llm_for_pipeline_test")
golden = _load(REPO_ROOT / "eval" / "setup_test_repo.py", "golden_repo_for_pipeline_test")


class _MockLLMResponse:
    def __init__(self, payload):
        self._payload = payload
        self.status_code = 200

    def json(self):
        return self._payload

    def raise_for_status(self):
        return None


@pytest.fixture
def offline_llm(monkeypatch):
    """Answer LLM calls like the mock server; fail every other POST fast."""
    real_post = httpx.AsyncClient.post

    async def post(self, url, *args, **kwargs):
        if str(url) == LLM_URL:
            body = kwargs.get("json") or {}
            messages = body.get("messages", [])
            system = messages[0]["content"] if messages and messages[0]["role"] == "system" else ""
            title = mock._schema_title(system)
            assert title, "no known schema title in the system message"
            prompt = "\n".join(str(m.get("content", "")) for m in messages)
            content = mock.json.dumps(mock.ANSWERS[title](prompt))
            return _MockLLMResponse({"choices": [{"message": {"content": content}, "finish_reason": "stop"}],
                                     "usage": {"total_tokens": 0}})
        raise httpx.ConnectError(f"offline test: no {url}")

    async def get(self, url, *args, **kwargs):
        raise httpx.ConnectError(f"offline test: no {url}")

    monkeypatch.setattr(httpx.AsyncClient, "post", post)
    monkeypatch.setattr(httpx.AsyncClient, "get", get)
    yield
    monkeypatch.setattr(httpx.AsyncClient, "post", real_post)


@pytest.fixture
def case1_repo(tmp_path):
    """Correct base commit, then the commit that removes the null guard."""
    repo = tmp_path / "repo"
    repo.mkdir()
    run = lambda *a: subprocess.run(["git", *a], cwd=repo, check=True, capture_output=True)  # noqa: E731
    run("init", "-q", "-b", "main")
    run("config", "user.email", "t@example.com")
    run("config", "user.name", "T")
    for rel, content in golden.BASE_FILES.items():
        (repo / rel).parent.mkdir(parents=True, exist_ok=True)
        (repo / rel).write_text(content)
    run("add", ".")
    run("commit", "-q", "-m", "Initial commit")
    case = next(c for c in golden.CASES if c.id == "case1_npe")
    for rel, content in case.bug_files.items():
        (repo / rel).write_text(content)
    run("commit", "-qam", case.bug_message)
    return repo, case


@pytest.fixture
def clients(monkeypatch, offline_llm):
    monkeypatch.setenv("GITORACLE_INTERNAL_TOKEN", TOKEN)
    from fastapi.testclient import TestClient
    apps = {name: _load(AI_CORE / "agents" / name / "main.py", f"pipeline_test_{name}").app
            for name in ("investigator", "planner", "fixer")}
    # Not used as context managers: startup hooks (Kafka consumers) don't run.
    return {name: TestClient(app, headers={"X-Internal-Token": TOKEN}) for name, app in apps.items()}


def test_investigate_plan_fix_produces_a_patch_that_applies(clients, case1_repo):
    repo, case = case1_repo
    common = {"tenant_id": "00000000-0000-0000-0000-000000000000", "repo_path": str(repo),
              # mock-test- makes the fixer skip its self-test call to the real test runner.
              "job_id": "mock-test-pipeline", "bug_description": case.description}

    investigation = clients["investigator"].post("/investigate", json={**common, "affected_files": []})
    assert investigation.status_code == 200, investigation.text
    found = investigation.json()
    assert found["ranked_causes"] and found["affected_files"] == ["src/main/java/com/example/UserService.java"]

    plan = clients["planner"].post("/plan", json={**common, "investigation_result": found})
    assert plan.status_code == 200, plan.text
    assert plan.json()["strategy"] == "surgical_patch"

    fix = clients["fixer"].post("/fix", json={**common, "plan": plan.json()})
    assert fix.status_code == 200, fix.text
    result = fix.json()
    assert result["success"] is True, result
    diff = result["patch"]["diff"]
    assert "+        if (user == null) {" in diff
    assert result["patch"]["authorized_files"] == ["src/main/java/com/example/UserService.java"]

    # The diff must apply to the repo it was generated against.
    patch_file = repo.parent / "fix.patch"
    patch_file.write_text(diff)
    applied = subprocess.run(["git", "apply", "--check", str(patch_file)], cwd=repo, capture_output=True, text=True)
    assert applied.returncode == 0, applied.stderr
