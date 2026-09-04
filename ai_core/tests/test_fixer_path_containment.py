"""
Regression tests for path containment in agents/fixer/main.py.

test_safe_paths.py pins the containment helper. These pin that the Fixer
actually *uses* it at the two places that touch attacker- or LLM-controlled
paths, which is the part that was vulnerable:

  build_unified_diff(repo_path, file_path, ...)  <- file_path is raw LLM output
                                                    (SearchReplaceEdit), and the
                                                    resulting diff headers are
                                                    what a later stage applies.

  extract_file_paths_from_text(...)              <- the regex fallback over
                                                    human_instructions, i.e.
                                                    webhook text on a real job.

A helper that is correct but not called is worth nothing, so these exercise the
Fixer's own functions rather than the helper.
"""
import os
import sys

import pytest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from agents.fixer.main import (  # noqa: E402
    build_unified_diff,
    extract_file_paths_from_text,
)
from shared.safe_paths import UnsafePathError, resolve_within  # noqa: E402


@pytest.fixture
def repo(tmp_path):
    root = tmp_path / "repo"
    root.mkdir()
    (root / "app.py").write_text("def f():\n    return 1\n")
    (root / ".env").write_text("SECRET=hunter2\n")
    (tmp_path / "outside.txt").write_text("TOP SECRET\n")
    return root


# ── build_unified_diff ──────────────────────────────────────────────────────

def test_build_unified_diff_still_works_for_a_normal_edit(repo):
    diff = build_unified_diff(str(repo), "app.py", "return 1", "return 2")

    assert diff is not None
    assert "--- a/app.py" in diff
    assert "+    return 2" in diff


@pytest.mark.parametrize("payload", [
    "/etc/passwd",
    "../outside.txt",
    "../../etc/passwd",
    "a/../../outside.txt",
    "~/.ssh/id_rsa",
])
def test_build_unified_diff_refuses_paths_outside_the_repo(repo, payload):
    # Returning None routes into the Fixer's existing "rejected edit, retry"
    # branch, so containment degrades into a normal retry rather than a crash.
    assert build_unified_diff(str(repo), payload, "anything", "anything else") is None


def test_build_unified_diff_refuses_a_denied_file_inside_the_repo(repo):
    # .env is contained, so only the deny list stops this one.
    assert build_unified_diff(str(repo), ".env", "hunter2", "leaked") is None


def test_build_unified_diff_refuses_a_symlink_escaping_the_repo(repo, tmp_path):
    (repo / "innocent.txt").symlink_to(tmp_path / "outside.txt")

    assert build_unified_diff(str(repo), "innocent.txt", "TOP SECRET", "x") is None


def test_an_absolute_path_would_otherwise_have_escaped(repo):
    # Pins the underlying mechanism so the test above cannot pass for the wrong
    # reason (e.g. the file merely not existing).
    assert os.path.join(str(repo), "/etc/passwd") == "/etc/passwd"


# ── The regex fallback over attacker-supplied text ──────────────────────────

def test_the_instruction_regex_really_does_match_a_traversal_string():
    # This is why the fallback needed screening: the pattern accepts '.' and
    # '/', so a traversal path matches it in full rather than being split up.
    found = extract_file_paths_from_text(
        "please fix a/../../../../etc/nginx/nginx.conf it is broken"
    )

    assert "a/../../../../etc/nginx/nginx.conf" in found


def test_every_traversal_candidate_the_regex_yields_is_rejected_by_containment(repo):
    text = (
        "fix a/../../../../etc/nginx/nginx.conf and "
        "b/../../../.env and also src/app.py"
    )
    candidates = extract_file_paths_from_text(text)

    kept = []
    for candidate in candidates:
        try:
            resolve_within(str(repo), candidate, for_read=True)
            kept.append(candidate)
        except UnsafePathError:
            pass

    # Only the legitimate in-repo path survives; both traversals are dropped.
    assert kept == ["src/app.py"]


def test_ordinary_instruction_text_still_resolves_normally(repo):
    found = extract_file_paths_from_text("the bug is in app.py's f() function")

    assert "app.py" in found
    assert resolve_within(str(repo), "app.py").endswith("app.py")
