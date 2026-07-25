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
