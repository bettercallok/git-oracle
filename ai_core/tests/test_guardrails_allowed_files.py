"""
Regression test for scan_patch() in agents/guardrails/patch_scanner.py.

Context: the orchestrator called Guardrails with an always-empty
allowed_files list. scan_patch computes `unauthorized = touched_files -
allowed_files`, so an empty list means every touched file is rejected —
this silently blocked every fix ever generated, confirmed live: guardrails
rejected a patch for "touching" UserService.java on a job whose entire
purpose was fixing UserService.java. The fix threads the fixer's real
files_modified through instead of sending an empty list. This test pins
both directions so neither the empty-list-is-deny-all behavior nor a
future "just pass everything" over-correction can regress silently.
"""
import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "agents", "guardrails"))

from patch_scanner import scan_patch  # noqa: E402

SIMPLE_PATCH = """--- a/src/main/java/com/example/UserService.java
+++ b/src/main/java/com/example/UserService.java
@@ -8,6 +8,9 @@
     }

     public void processPayment(User user, PaymentInfo payment) {
+        if (user == null) {
+            throw new IllegalArgumentException("User cannot be null");
+        }
         String userId = user.getId();
     }
 }
"""


def test_empty_allowed_files_rejects_the_file_the_patch_actually_fixes():
    """This is the exact bug: an empty allow-list must not silently mean
    "everything is allowed" — but it also must not stay this way forever
    once callers start passing the real file list (see the next test)."""
    result = scan_patch(SIMPLE_PATCH, allowed_files=[])

    assert result.safe is False
    assert any("unauthorized files" in v for v in result.violations)


def test_correct_allowed_files_lets_the_patch_through():
    """The actual fix: when the caller passes the file(s) the patch is
    supposed to touch, that patch is no longer rejected."""
    result = scan_patch(
        SIMPLE_PATCH,
        allowed_files=["src/main/java/com/example/UserService.java"],
    )

    assert result.safe is True
    assert result.violations == []


def test_still_rejects_files_outside_the_allowed_set():
    """Guardrails should still catch a patch that touches a file it wasn't
    authorized to touch, even when the allow-list is non-empty."""
    result = scan_patch(
        SIMPLE_PATCH,
        allowed_files=["some/other/File.java"],
    )

    assert result.safe is False
    assert any("unauthorized files" in v for v in result.violations)


def _patch_touching(path: str) -> str:
    return f"""--- a/{path}
+++ b/{path}
@@ -1,1 +1,1 @@
-old
+new
"""


def test_denied_workflow_path_rejected_even_when_explicitly_allowed():
    """A plan/fixer completion naming .github/workflows/ci.yml in its own
    output must not be able to authorize touching it — deny-globs are a hard
    boundary independent of allowed_files, precisely so a manipulated
    completion can't grant itself CI-workflow write access just by listing
    the path."""
    patch = _patch_touching(".github/workflows/ci.yml")
    result = scan_patch(patch, allowed_files=[".github/workflows/ci.yml"])

    assert result.safe is False
    assert any("denied paths" in v for v in result.violations)


def test_denied_env_and_pem_paths_rejected():
    for path in [".env", ".env.production", "secrets/github-app.pem"]:
        result = scan_patch(_patch_touching(path), allowed_files=[path])
        assert result.safe is False, f"expected {path} to be denied"
        assert any("denied paths" in v for v in result.violations)


def test_denied_lockfiles_rejected():
    for path in ["package-lock.json", "backend/yarn.lock", "Cargo.lock"]:
        result = scan_patch(_patch_touching(path), allowed_files=[path])
        assert result.safe is False, f"expected {path} to be denied"
        assert any("denied paths" in v for v in result.violations)


def test_absolute_path_and_traversal_rejected_even_when_allowed():
    for path in ["/etc/passwd", "../../etc/passwd", "a/../../b.py"]:
        result = scan_patch(_patch_touching(path), allowed_files=[path])
        assert result.safe is False, f"expected {path} to be denied"
        assert any("denied paths" in v for v in result.violations)


def test_ordinary_authorized_file_still_passes_with_deny_globs_in_place():
    """The new deny-glob check must not become an accidental deny-all —
    a normal, correctly-authorized source file still passes."""
    result = scan_patch(SIMPLE_PATCH, allowed_files=["src/main/java/com/example/UserService.java"])
    assert result.safe is True
    assert result.violations == []
