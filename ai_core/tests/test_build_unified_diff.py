"""
Regression tests for build_unified_diff() in agents/fixer/main.py.

Context: asking the LLM to hand-write a unified diff was the single biggest
blocker to any job ever reaching SUCCESS this session — every live patch
failed git apply with "corrupt patch" (hallucinated line numbers, truncated
hunks). Replaced with search/replace text computed into a diff via
difflib.unified_diff(), which can never produce invalid diff syntax, plus a
brace-balance check that rejects edits producing code that can't compile.
These are pure functions with no network/LLM dependency, so they're cheap
to keep honest in CI — this exact regression is easy to silently
reintroduce (everything still "runs", it just never finishes correctly).
"""
import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from agents.fixer.main import build_unified_diff  # noqa: E402

ORIGINAL = """package com.example;

public class UserService {
    public void processPayment(User user) {
        String userId = user.getId();
    }
}
"""


def _write_repo(tmp_path, content=ORIGINAL):
    file_path = "src/main/java/com/example/UserService.java"
    full_path = tmp_path / file_path
    full_path.parent.mkdir(parents=True, exist_ok=True)
    full_path.write_text(content)
    return file_path


def test_produces_a_diff_that_git_apply_accepts(tmp_path):
    file_path = _write_repo(tmp_path)

    diff = build_unified_diff(
        str(tmp_path),
        file_path,
        search="        String userId = user.getId();",
        replace=(
            "        if (user == null) {\n"
            '            throw new IllegalArgumentException("User cannot be null");\n'
            "        }\n"
            "        String userId = user.getId();"
        ),
    )

    assert diff is not None
    assert diff.startswith("---")
    assert "+++" in diff
    assert "IllegalArgumentException" in diff

    # The real proof: does git actually accept it? A hand-written-by-the-LLM
    # diff routinely didn't; a difflib-computed one always should.
    import subprocess

    patch_file = tmp_path / "test.patch"
    patch_file.write_text(diff)
    result = subprocess.run(
        ["git", "apply", "--check", str(patch_file)],
        cwd=str(tmp_path),
        capture_output=True,
        text=True,
    )
    assert result.returncode == 0, f"git apply --check failed: {result.stderr}"


def test_returns_none_when_search_text_is_not_found(tmp_path):
    file_path = _write_repo(tmp_path)

    diff = build_unified_diff(
        str(tmp_path),
        file_path,
        search="this text does not appear anywhere in the file",
        replace="replacement",
    )

    assert diff is None


def test_rejects_edit_that_leaves_braces_unbalanced(tmp_path):
    file_path = _write_repo(tmp_path)

    # A replace block that opens a brace it never closes — the exact failure
    # mode confirmed live ("reached end of file while parsing").
    diff = build_unified_diff(
        str(tmp_path),
        file_path,
        search="        String userId = user.getId();",
        replace="        if (user == null) {\n        String userId = user.getId();",
    )

    assert diff is None


def test_returns_none_when_file_does_not_exist(tmp_path):
    diff = build_unified_diff(
        str(tmp_path),
        "does/not/exist.java",
        search="anything",
        replace="anything else",
    )

    assert diff is None
