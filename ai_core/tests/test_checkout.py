"""shared/checkout.py: an agent on another host clones the job's repo itself, at its base commit."""
import os
import subprocess
import time

import pytest

import shared.checkout as checkout


def _git(cwd, *args):
    return subprocess.run(["git", "-C", str(cwd), *args], check=True, capture_output=True, text=True).stdout.strip()


@pytest.fixture
def upstream(tmp_path):
    """A repository with two commits; returns (path, first sha, second sha)."""
    repo = tmp_path / "upstream"
    repo.mkdir()
    _git(repo, "init", "-q", "-b", "main")
    _git(repo, "config", "user.email", "t@example.com")
    _git(repo, "config", "user.name", "t")
    (repo / "app.py").write_text("v1\n")
    _git(repo, "add", ".")
    _git(repo, "commit", "-q", "-m", "one")
    first = _git(repo, "rev-parse", "HEAD")
    (repo / "app.py").write_text("v2\n")
    _git(repo, "commit", "-q", "-am", "two")
    return repo, first, _git(repo, "rev-parse", "HEAD")


@pytest.fixture
def root(tmp_path, monkeypatch):
    ws = tmp_path / "workspaces"
    ws.mkdir()
    monkeypatch.setenv("GITORACLE_WORKSPACE_ROOT", str(ws))
    return ws


@pytest.fixture
def local_urls(monkeypatch):
    # The real validator only allows https://github.com; these tests clone a local repo.
    monkeypatch.setattr(checkout, "validate_repo_url", lambda url: url)


def test_an_existing_checkout_is_used_as_it_is(root, upstream, local_urls):
    repo, first, _ = upstream
    assert checkout.ensure_checkout(str(repo), str(repo), None, first) == str(repo)
    assert (repo / "app.py").read_text() == "v2\n"


def test_a_missing_checkout_is_cloned_at_the_base_commit(root, upstream, local_urls):
    repo, first, _ = upstream
    target = root / "job-1"

    assert checkout.ensure_checkout(str(target), str(repo), "main", first) == str(target)

    assert checkout.head_commit(str(target)) == first
    assert (target / "app.py").read_text() == "v1\n"
    # The history before it is there for the investigator.
    assert _git(target, "log", "--format=%s") == "one"
    assert not [p for p in os.listdir(root) if ".cloning-" in p]


def test_without_a_base_commit_it_reads_the_branch(root, upstream, local_urls):
    repo, _, second = upstream
    target = root / "job-2"
    assert checkout.ensure_checkout(str(target), str(repo), None, "") == str(target)
    assert checkout.head_commit(str(target)) == second


def test_an_unknown_base_commit_still_leaves_a_checkout(root, upstream, local_urls):
    repo, _, second = upstream
    target = root / "job-3"
    assert checkout.ensure_checkout(str(target), str(repo), None, "deadbeef" * 5) == str(target)
    assert checkout.head_commit(str(target)) == second


def test_nothing_is_cloned_outside_the_workspace_root(root, upstream, tmp_path, local_urls):
    repo, first, _ = upstream
    outside = tmp_path / "elsewhere"
    assert checkout.ensure_checkout(str(outside), str(repo), None, first) == ""
    assert not outside.exists()


def test_only_github_urls_are_cloned(root):
    assert checkout.ensure_checkout(str(root / "job-4"), "file:///etc", None, "") == ""
    assert not (root / "job-4").exists()


def test_old_workspaces_are_swept_only_when_asked(root, upstream, local_urls, monkeypatch):
    repo, first, _ = upstream
    stale = root / "stale-job"
    stale.mkdir()
    old = time.time() - 3 * 3600
    os.utime(stale, (old, old))

    checkout.ensure_checkout(str(root / "job-5"), str(repo), None, first)
    assert stale.exists()

    monkeypatch.setenv("GITORACLE_WORKSPACE_MAX_AGE_HOURS", "2")
    checkout.ensure_checkout(str(root / "job-6"), str(repo), None, first)
    assert not stale.exists()
    assert (root / "job-5").exists()
