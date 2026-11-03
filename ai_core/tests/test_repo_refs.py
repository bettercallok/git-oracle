"""
shared/repo_refs: what the Fixer will hand to git, and which directories it
will delete. The Fixer used to clone any request.repo_url (its own model
documented "GitHub or file://") and rmtree any request.repo_path lacking .git.
"""
import os
import sys

import pytest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "agents", "guardrails"))

from shared.repo_refs import (  # noqa: E402
    InvalidRepoRefError,
    is_within_workspace_root,
    validate_branch,
    validate_repo_url,
)
from patch_scanner import scan_patch  # noqa: E402


@pytest.mark.parametrize("url", [
    "https://github.com/acme/app",
    "https://github.com/acme/app.git",
    "https://GitHub.com/a.b/c_d-e",
])
def test_accepts_plain_github_https_urls(url):
    assert validate_repo_url(url) == url


@pytest.mark.parametrize("url", [
    "file:///etc",
    "/home/user/private-repo",
    "ext::sh -c touch% /tmp/pwned",
    "--upload-pack=touch /tmp/pwned",
    "http://github.com/acme/app",
    "https://evil.example/acme/app",
    "https://github.com.evil.example/acme/app",
    "https://token@github.com/acme/app",
    "https://github.com:8443/acme/app",
    "https://github.com/acme/app?x=1",
    "https://github.com/acme/../../etc",
    "https://github.com/acme",
    "",
])
def test_rejects_everything_else(url):
    with pytest.raises(InvalidRepoRefError):
        validate_repo_url(url)


def test_branch_validation():
    assert validate_branch(None) is None
    assert validate_branch("  ") is None
    assert validate_branch("feature/x-1") == "feature/x-1"
    for bad in ["-c", "--upload-pack=x", "a..b", "a b", "x.lock", "a@{1}"]:
        with pytest.raises(InvalidRepoRefError):
            validate_branch(bad)


def test_only_paths_strictly_inside_the_workspace_root_may_be_deleted(tmp_path, monkeypatch):
    root = tmp_path / "workspaces"
    root.mkdir()
    monkeypatch.setenv("GITORACLE_WORKSPACE_ROOT", str(root))

    assert is_within_workspace_root(str(root / "9e09ccf6"))
    assert is_within_workspace_root(str(root / "9e09ccf6-fixer-src"))
    assert not is_within_workspace_root(str(root))
    assert not is_within_workspace_root(str(root / ".." / "elsewhere"))
    assert not is_within_workspace_root(str(tmp_path / "workspaces-evil" / "x"))
    assert not is_within_workspace_root("/home")
    assert not is_within_workspace_root("")

    # A symlink inside the root pointing out of it does not count as inside.
    outside = tmp_path / "outside"
    outside.mkdir()
    (root / "link").symlink_to(outside)
    assert not is_within_workspace_root(str(root / "link"))


def _patch_touching(path: str) -> str:
    return (f"diff --git a/{path} b/{path}\n--- a/{path}\n+++ b/{path}\n"
            "@@ -1 +1 @@\n-old\n+new\n")


@pytest.mark.parametrize("path", [".github/actions/setup/action.yml", "svc/.github/actions/x/run.sh", ".gitmodules"])
def test_guardrails_deny_ci_adjacent_paths_even_when_authorized(path):
    result = scan_patch(_patch_touching(path), allowed_files=[path])
    assert not result.safe
    assert any("denied" in v for v in result.violations)
