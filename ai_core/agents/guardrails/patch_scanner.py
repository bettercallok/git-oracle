import os
import re
import sys

from pydantic import BaseModel

# main.py performs this same bootstrap, but it does so *after* importing this
# module, so this file cannot rely on it having happened yet.
sys.path.append(os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))))
from shared.safe_paths import DENIED_GLOBS, is_denied_path  # noqa: E402,F401


class PatchScanResult(BaseModel):
    safe: bool
    violations: list[str]
    # Non-gating observations. See CONTENT_HEURISTICS below for why the content
    # scan reports rather than blocks.
    advisories: list[str] = []
    # Every path this patch was found to touch, so a caller can log or display
    # what the decision was actually made about.
    touched_files: list[str] = []


# ---------------------------------------------------------------------------
# Content heuristics — ADVISORY ONLY, deliberately not a gate.
#
# These were violations, i.e. a patch containing "eval(" anywhere was rejected
# and the job marked FAILED. That is false assurance in both directions:
#
#   It does not stop an attacker. Every one of these is a substring match on
#   source text, and trivially avoided —
#       getattr(__builtins__, "ev" + "al")("...")
#       os.popen(...)                     # not "subprocess."
#       __import__("os").system(...)      # not "os.system("
#   A determined attacker writes the second form without noticing the first was
#   ever blocked. No regex list stops code that can rewrite itself; a real
#   content gate needs AST or semgrep analysis, and even that is not a boundary.
#
#   It does block honest patches. The scan ran over the ENTIRE diff text, so an
#   unchanged CONTEXT line containing "subprocess." failed the patch — a fix to
#   a file that legitimately shells out could never pass, no matter what the
#   change actually was.
#
# What actually contains a malicious patch is: the sandbox tests run in
# (--network=none, non-root, read-only, cap-drop=ALL), the authorized-files
# allowlist and denied-path list enforced below as real gates, and human review
# of the resulting pull request. This list's job is to draw a reviewer's eye,
# which it can do honestly. Presenting it as a security boundary is what was
# dishonest.
# ---------------------------------------------------------------------------
CONTENT_HEURISTICS = [
    (r"os\.system\(", "Shell execution"),
    (r"\beval\(", "Dynamic code evaluation"),
    (r"\bexec\(", "Dynamic code execution"),
    (r"subprocess\.", "Subprocess invocation"),
    (r"(?i)(api_key|secret|password|token)\s*=\s*['\"][^'\"]{8,}", "Possible hardcoded secret"),
    (r"(?i)rm\s+-rf", "Destructive filesystem command"),
    (r"DROP\s+TABLE", "SQL DROP TABLE"),
]

# Retained under its old name so anything importing it keeps working.
DANGER_PATTERNS = CONTENT_HEURISTICS

SCOPE_LIMIT = 50  # max lines changed per patch

# DENIED_GLOBS now lives in shared/safe_paths.py (imported above) rather than
# here. The Fixer needs the identical boundary when it *reads* files into an LLM
# prompt, and two hand-maintained copies of a security list are two lists that
# will eventually disagree — the one that matters being whichever is checked
# last.


def _is_denied_path(path: str) -> bool:
    # Absolute paths and traversal are never valid here — every legitimate
    # touched-file path in a patch against a checked-out repo is relative.
    return is_denied_path(path, DENIED_GLOBS)


def scan_patch(patch_diff: str, allowed_files: list[str]) -> PatchScanResult:
    violations: list[str] = []
    advisories: list[str] = []

    # 1. Content heuristics — advisory, and only over ADDED lines. Scanning the
    #    whole diff meant an unchanged context line could sink a patch.
    added_text = "\n".join(_added_lines(patch_diff))
    for pattern, message in CONTENT_HEURISTICS:
        if re.search(pattern, added_text):
            advisories.append(message)

    # 2. Which files does this patch actually touch?
    touched_files, parse_errors = extract_touched_paths(patch_diff)

    # Fail closed. A patch we cannot fully parse is a patch whose blast radius
    # is unknown, and "unknown" must not read as "nothing to check".
    violations.extend(parse_errors)

    if patch_diff.strip() and not touched_files and not parse_errors:
        # This is precisely the old bypass: a rename-only, copy-only,
        # delete-only, or binary patch produced NO "+++ b/" line, so the file
        # set came back empty and every check below silently passed on it.
        violations.append(
            "Patch is non-empty but no touched files could be identified — refusing to validate a patch whose scope is unknown."
        )

    # 3. Authorized-files gate.
    unauthorized = sorted(set(touched_files) - set(allowed_files))
    if unauthorized:
        violations.append(f"Patch touches unauthorized files: {unauthorized}")

    # 3b. Denied paths are rejected even if somehow present in allowed_files —
    # a hard boundary independent of the allowlist above.
    denied = sorted(f for f in touched_files if _is_denied_path(f))
    if denied:
        violations.append(f"Patch touches denied paths: {denied}")

    # 4. Size limit.
    lines_changed = _count_diff_lines(patch_diff)
    if lines_changed > SCOPE_LIMIT:
        violations.append(f"Patch too large: {lines_changed} lines (max {SCOPE_LIMIT})")

    return PatchScanResult(
        safe=len(violations) == 0,
        violations=violations,
        advisories=advisories,
        touched_files=sorted(touched_files),
    )


# ---------------------------------------------------------------------------
# Diff parsing
# ---------------------------------------------------------------------------

_DIFF_GIT_RE = re.compile(r'^diff --git (?P<a>"[^"]+"|\S+) (?P<b>"[^"]+"|\S+)\s*$')
_BINARY_FILES_RE = re.compile(r'^Binary files (?P<a>.+?) and (?P<b>.+?) differ\s*$')


def _strip_prefix(path: str) -> str:
    """Remove git's a/ or b/ operand prefix and surrounding quotes."""
    path = path.strip()
    if len(path) >= 2 and path[0] == '"' and path[-1] == '"':
        # Git quotes paths containing special characters and escapes them
        # C-style. unicode_escape is a close enough decoding for comparison
        # purposes, and falling back to the raw value is the safe direction:
        # a path we decode imperfectly still gets compared against the
        # allowlist, it just may not match and will therefore be rejected.
        inner = path[1:-1]
        try:
            path = inner.encode("latin-1", "backslashreplace").decode("unicode_escape")
        except (UnicodeDecodeError, UnicodeEncodeError):
            path = inner
    if path.startswith(("a/", "b/")):
        path = path[2:]
    return path.strip()


def extract_touched_paths(diff: str) -> tuple[set[str], list[str]]:
    """
    Every path a patch references, from every header form git can emit, plus a
    list of parse errors.

    This used to read only lines beginning "+++ b/", which meant any patch form
    that does not produce one was reported as touching NOTHING — and a file set
    of nothing passes an allowlist check and a denied-path check trivially.
    Confirmed against the previous implementation, all returning safe=True with
    an allowlist that authorized none of them:

        rename from README.md / rename to .github/workflows/ci.yml
        copy from README.md   / copy to   .github/workflows/evil.yml
        deleted file mode ... (the path appears only on the "--- a/" line)
        GIT binary patch      (no ---/+++ lines at all)

    The union of source and destination is taken deliberately: a rename both
    removes one path and creates another, and both halves need authorizing.
    """
    paths: set[str] = set()
    errors: list[str] = []

    if not diff or not diff.strip():
        return paths, errors

    def add(raw: str) -> None:
        cleaned = _strip_prefix(raw)
        # /dev/null is git's marker for "this side does not exist" on a create
        # or delete — it is not a path anyone touches.
        if cleaned and cleaned != "/dev/null":
            paths.add(cleaned)

    saw_diff_git = False

    for line in diff.split("\n"):
        if line.startswith("diff --git "):
            saw_diff_git = True
            match = _DIFF_GIT_RE.match(line)
            if not match:
                # A header we cannot read is a file we cannot account for.
                errors.append(f"Unparseable diff header: {line.strip()[:120]!r}")
                continue
            add(match.group("a"))
            add(match.group("b"))

        elif line.startswith("--- "):
            add(line[4:])
        elif line.startswith("+++ "):
            add(line[4:])

        elif line.startswith("rename from "):
            add(line[len("rename from "):])
        elif line.startswith("rename to "):
            add(line[len("rename to "):])
        elif line.startswith("copy from "):
            add(line[len("copy from "):])
        elif line.startswith("copy to "):
            add(line[len("copy to "):])

        elif line.startswith("Binary files "):
            match = _BINARY_FILES_RE.match(line)
            if match:
                add(match.group("a"))
                add(match.group("b"))

    # A diff that carries hunks but never named a file in any recognised header
    # is malformed or uses a format this parser does not know. Either way its
    # scope is unknown.
    if not paths and not saw_diff_git and ("@@" in diff or diff.strip()):
        errors.append("Could not identify any file paths in the patch.")

    return paths, errors


def _added_lines(diff: str) -> list[str]:
    """Only lines the patch INTRODUCES — not context, not removed lines."""
    out = []
    for line in diff.split("\n"):
        if line.startswith("+") and not line.startswith("+++"):
            out.append(line[1:])
    return out


def _count_diff_lines(diff: str) -> int:
    # Count added and removed lines
    changes = 0
    for line in diff.split('\n'):
        if (line.startswith('+') and not line.startswith('+++')) or \
           (line.startswith('-') and not line.startswith('---')):
            changes += 1
    return changes


def _extract_files_from_diff(diff: str) -> list[str]:
    """
    Backwards-compatible wrapper. Prefer extract_touched_paths, which also
    reports parse errors — discarding those is how a patch with an
    unreadable header ends up looking clean.
    """
    paths, _ = extract_touched_paths(diff)
    return sorted(paths)
