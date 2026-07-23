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

SCOPE_LIMIT = 50  # max lines changed per patch (configurable)

def count_diff_lines(patch_diff: str) -> int:
    return len([line for line in patch_diff.split('\n') if line.startswith('+') or line.startswith('-')])

def extract_files_from_diff(patch_diff: str) -> list[str]:
    files = set()
    for line in patch_diff.split('\n'):
        if line.startswith('+++ b/'):
            files.add(line[6:])
    return list(files)

def scan_patch(patch_diff: str, approved_files: list[str]) -> PatchScanResult:
    violations = []
    
    # 1. Pattern Matching
    for pattern, message in DANGER_PATTERNS:
        if re.search(pattern, patch_diff):
            violations.append(message)
    
    # 2. Scope Check: touch authorized files only
    touched_files = extract_files_from_diff(patch_diff)
    unauthorized = set(touched_files) - set(approved_files)
    if unauthorized:
        violations.append(f"Patch touches unauthorized files: {unauthorized}")
    
    # 3. Lines Changed Limit Check
    lines_changed = count_diff_lines(patch_diff)
    if lines_changed > SCOPE_LIMIT:
        violations.append(f"Patch too large: {lines_changed} lines (max {SCOPE_LIMIT})")
    
    return PatchScanResult(safe=len(violations) == 0, violations=violations)
