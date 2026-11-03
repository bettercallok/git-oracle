"""commit_diff: the bug-introducing change shown to the planner and fixer.

The sha comes from LLM output, so anything but a plain hex object name must be
refused before it reaches git (an option like --output=... would otherwise be
parsed by `git show`)."""
import os
import subprocess
import sys

import pytest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from shared.git_context import commit_diff  # noqa: E402


@pytest.fixture
def repo(tmp_path):
    git = lambda *a: subprocess.run(["git", *a], cwd=tmp_path, check=True, capture_output=True, text=True)  # noqa: E731
    git("init", "-q"); git("config", "user.email", "t@example.com"); git("config", "user.name", "T")
    (tmp_path / "Limiter.java").write_text("return n < limit;\n")
    (tmp_path / "Other.java").write_text("a\n")
    git("add", "."); git("commit", "-qm", "base")
    (tmp_path / "Limiter.java").write_text("return n <= limit;\n")
    (tmp_path / "Other.java").write_text("b\n")
    git("commit", "-qam", "tweak limits")
    return tmp_path, git("rev-parse", "HEAD").stdout.strip()


def test_returns_what_the_commit_changed_in_the_given_files(repo):
    path, sha = repo
    diff = commit_diff(str(path), sha, ["Limiter.java"])

    assert "-return n < limit;" in diff and "+return n <= limit;" in diff
    assert "Other.java" not in diff
    assert "tweak limits" in diff


def test_falls_back_to_the_whole_commit_when_the_files_are_untouched(repo):
    path, sha = repo
    diff = commit_diff(str(path), sha, ["NotChanged.java"])

    assert "Limiter.java" in diff and "Other.java" in diff


@pytest.mark.parametrize("bad", ["--output=/tmp/pwned", "HEAD", "abc", "", None, "zzzzzzz", "a" * 41])
def test_anything_but_a_hex_sha_is_refused(repo, bad):
    path, _ = repo
    assert commit_diff(str(path), bad, ["Limiter.java"]) == ""


def test_no_repo_or_unknown_commit_gives_nothing(repo, tmp_path):
    path, _ = repo
    assert commit_diff(str(tmp_path / "missing"), "abcdef1") == ""
    assert commit_diff(str(path), "abcdef1234567") == ""


def test_the_diff_is_clipped(repo):
    path, sha = repo
    assert len(commit_diff(str(path), sha, max_chars=40)) <= 40 + len("\n[...diff truncated]")
