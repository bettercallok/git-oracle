"""What a commit actually changed, as context for the planner and the fixer.

The planner used to see only the top suspect commit's *message* ("refactor:
simplify processPayment") plus the investigator's narrative, and had to guess
what changed. On the rate-limiter eval case it guessed the direction of an
off-by-one backwards ("change >= to >" for a bug that admitted one request too
many) and the fixer followed it. For a regression, the bug-introducing diff is
the strongest evidence there is: the fix is usually to undo part of it.
"""
import logging
import os
import re
import subprocess
from typing import Iterable, Optional

logger = logging.getLogger(__name__)

# The sha comes from LLM output (the investigator's ranked causes). Only a plain
# hex object name may reach git — never anything that could parse as an option.
_SHA_RE = re.compile(r"^[0-9a-fA-F]{7,40}$")


def _git_show(repo_path: str, sha: str, files: list[str]) -> Optional[str]:
    cmd = ["git", "-C", repo_path, "show", "--no-color", "--format=commit %h %s", "--unified=3", sha, "--", *files]
    try:
        result = subprocess.run(cmd, capture_output=True, text=True, timeout=15, check=False)
    except (OSError, subprocess.SubprocessError) as e:
        logger.warning(f"Could not read commit {sha}: {e}")
        return None
    return result.stdout if result.returncode == 0 else None


def commit_diff(repo_path: str, sha: Optional[str], files: Iterable[str] = (), max_chars: int = 3000) -> str:
    """The diff `sha` introduced, limited to `files` when it touched any of them
    (else the whole commit), clipped to `max_chars`. Empty when unavailable."""
    if not sha or not _SHA_RE.match(sha) or not repo_path or not os.path.isdir(os.path.join(repo_path, ".git")):
        return ""
    paths = [f for f in files if f and not f.startswith("-")]
    text = _git_show(repo_path, sha, paths) if paths else None
    if not text or "diff --git" not in text:
        text = _git_show(repo_path, sha, [])
    if not text or "diff --git" not in text:
        return ""
    text = text.strip()
    return text if len(text) <= max_chars else text[:max_chars] + "\n[...diff truncated]"
