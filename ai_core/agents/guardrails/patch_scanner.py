import fnmatch
import re
from pydantic import BaseModel

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

# Never authorized, regardless of allowed_files — an LLM-generated plan is not
# a trust anchor for touching these; a compromised or manipulated planner/fixer
# completion must not be able to authorize its own privilege escalation (e.g.
# rewriting the CI workflow that will later run with real credentials) just by
# naming the path in its own output.
DENIED_GLOBS = [
    ".git/**", "**/.git/**",
    ".github/workflows/**", "**/.github/workflows/**",
    "*.pem", "**/*.pem",
    ".env", ".env.*", "**/.env", "**/.env.*",
    "package-lock.json", "**/package-lock.json",
    "yarn.lock", "**/yarn.lock",
    "pnpm-lock.yaml", "**/pnpm-lock.yaml",
    "Gemfile.lock", "**/Gemfile.lock",
    "poetry.lock", "**/poetry.lock",
    "Cargo.lock", "**/Cargo.lock",
    "go.sum", "**/go.sum",
]

def _is_denied_path(path: str) -> bool:
    # Absolute paths and traversal are never valid here — every legitimate
    # touched-file path in a patch against a checked-out repo is relative.
    if path.startswith("/") or path.startswith("~") or ".." in path.split("/"):
        return True
    return any(fnmatch.fnmatch(path, pattern) for pattern in DENIED_GLOBS)

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
