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

DANGER_PATTERNS = [
    (r"os\.system\(", "Shell injection risk"),
    (r"eval\(", "Code injection risk"),
    (r"exec\(", "Code injection risk"),
    (r"subprocess\.", "Shell command risk"),
    (r"(?i)(api_key|secret|password|token)\s*=\s*['\"][^'\"]{8,}", "Hardcoded secret"),
    (r"(?i)rm\s+-rf", "Destructive filesystem command"),
    (r"DROP\s+TABLE", "SQL destruction risk"),
]

SCOPE_LIMIT = 50  # max lines changed per patch

# DENIED_GLOBS now lives in shared/safe_paths.py (imported above) rather than
# here. The Fixer needs the identical boundary when it *reads* files into an LLM
# prompt, and two hand-maintained copies of a security list are two lists that
# will eventually disagree — the one that matters being whichever is checked
# last. Behaviour of this module is unchanged; only the list's home moved.

def _is_denied_path(path: str) -> bool:
    # Absolute paths and traversal are never valid here — every legitimate
    # touched-file path in a patch against a checked-out repo is relative.
    return is_denied_path(path, DENIED_GLOBS)

def scan_patch(patch_diff: str, allowed_files: list[str]) -> PatchScanResult:
    violations = []

    # 1. Check for danger patterns
    for pattern, message in DANGER_PATTERNS:
        if re.search(pattern, patch_diff):
            violations.append(message)

    # 2. Check for unauthorized file touches
    touched_files = _extract_files_from_diff(patch_diff)
    unauthorized = set(touched_files) - set(allowed_files)
    if unauthorized:
        violations.append(f"Patch touches unauthorized files: {list(unauthorized)}")

    # 2b. Denied paths are rejected even if somehow present in allowed_files —
    # this is a hard boundary independent of the allowlist above.
    denied = [f for f in touched_files if _is_denied_path(f)]
    if denied:
        violations.append(f"Patch touches denied paths: {denied}")

    # 3. Check for size limit
    lines_changed = _count_diff_lines(patch_diff)
    if lines_changed > SCOPE_LIMIT:
        violations.append(f"Patch too large: {lines_changed} lines (max {SCOPE_LIMIT})")

    return PatchScanResult(
        safe=len(violations) == 0,
        violations=violations
    )

def _extract_files_from_diff(diff: str) -> list[str]:
    # Extract file names from diff header, e.g. "+++ b/src/main.py"
    files = []
    for line in diff.split('\n'):
        if line.startswith('+++ b/'):
            files.append(line[6:].strip())
    return files

def _count_diff_lines(diff: str) -> int:
    # Count added and removed lines
    changes = 0
    for line in diff.split('\n'):
        if (line.startswith('+') and not line.startswith('+++')) or \
           (line.startswith('-') and not line.startswith('---')):
            changes += 1
    return changes
