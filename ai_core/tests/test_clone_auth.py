"""shared/clone_auth.py: private repositories clone with the orchestrator's read token."""
import base64
import os
import subprocess

import httpx

import shared.clone_auth as clone_auth

JOB = "0b8a6f5e-1c2d-4e3f-8a9b-0c1d2e3f4a5b"


class _Response:
    def __init__(self, status, body=None):
        self.status_code = status
        self._body = body

    def json(self):
        if self._body is None:
            raise ValueError("no body")
        return self._body


def test_the_token_is_fetched_for_the_job(monkeypatch):
    seen = {}

    def post(url, headers, timeout):
        seen["url"], seen["headers"] = url, headers
        return _Response(200, {"token": "ghs_abc", "expires_at": "2026-01-01T00:00:00Z"})

    monkeypatch.setenv("ORCHESTRATOR_URL", "http://orchestrator:8083/")
    monkeypatch.setenv("GITORACLE_INTERNAL_TOKEN", "internal")
    monkeypatch.setattr(clone_auth.httpx, "post", post)

    assert clone_auth.fetch_clone_token(JOB) == "ghs_abc"
    assert seen["url"] == f"http://orchestrator:8083/api/v1/jobs/{JOB}/clone-token"
    assert seen["headers"] == {"X-Internal-Token": "internal"}


def test_no_token_means_an_anonymous_clone(monkeypatch):
    monkeypatch.setattr(clone_auth.httpx, "post", lambda *a, **k: _Response(204))
    assert clone_auth.fetch_clone_token(JOB) is None

    def down(*a, **k):
        raise httpx.ConnectError("refused")

    monkeypatch.setattr(clone_auth.httpx, "post", down)
    assert clone_auth.fetch_clone_token(JOB) is None


def test_a_malformed_job_id_is_never_put_in_a_url(monkeypatch):
    def post(*a, **k):
        raise AssertionError("must not be called")

    monkeypatch.setattr(clone_auth.httpx, "post", post)
    assert clone_auth.fetch_clone_token("../admin") is None
    assert clone_auth.fetch_clone_token("") is None


def test_git_sends_the_token_but_never_stores_it(tmp_path):
    env = clone_auth.git_auth_env("ghs_secret")
    assert clone_auth.git_auth_env(None) == {}

    # git reads it as configuration for github.com only...
    out = subprocess.run(["git", "config", "--get", "http.https://github.com/.extraheader"],
                         capture_output=True, text=True, env={**os.environ, **env}, cwd=tmp_path)
    expected = base64.b64encode(b"x-access-token:ghs_secret").decode()
    assert out.stdout.strip() == f"AUTHORIZATION: basic {expected}"

    # ...and a clone made with it keeps none of it.
    src = tmp_path / "src"
    src.mkdir()
    subprocess.run(["git", "init", "-q", str(src)], check=True)
    subprocess.run(["git", "-C", str(src), "-c", "user.email=t@e", "-c", "user.name=t",
                    "commit", "-q", "--allow-empty", "-m", "x"], check=True)
    dst = tmp_path / "dst"
    subprocess.run(["git", "clone", "-q", str(src), str(dst)], check=True, env={**os.environ, **env})
    assert "ghs_secret" not in (dst / ".git" / "config").read_text()
    assert expected not in (dst / ".git" / "config").read_text()
