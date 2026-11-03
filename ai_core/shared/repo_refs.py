"""
Validation for repository references an agent hands to git, and for the
workspace directories it is willing to delete.

Mirrors ``ai.gitoracle.core.security.RepoRefValidator`` on the Java side. Every
Java call site that reaches ``git`` validates first; the Fixer's
``git.Repo.clone_from(request.repo_url, ...)`` did not, and its request model
documented ``repo_url`` as "GitHub or file://". GitPython refuses ``ext::`` and
option-shaped URLs by default, but ``file://`` (or a bare local path) clones a
repository from this host's own filesystem into a checkout whose files are then
read into an LLM prompt — a read primitive for anything git can open locally.

The same request also carried ``repo_path``, which the Fixer would
``shutil.rmtree`` whenever it did not already contain a ``.git`` directory.
:func:`is_within_workspace_root` confines that to the pipeline's own workspaces.
"""

import os
import re
from typing import Optional
from urllib.parse import urlsplit

_OWNER_REPO = re.compile(r"[A-Za-z0-9._-]{1,100}/[A-Za-z0-9._-]{1,100}")
_BRANCH = re.compile(r"[A-Za-z0-9._/-]{1,255}")
ALLOWED_HOSTS = frozenset({"github.com"})

DEFAULT_WORKSPACE_ROOT = "/tmp/gitoracle-workspaces"


class InvalidRepoRefError(ValueError):
    pass


def validate_repo_url(repo_url: str) -> str:
    """Return the URL if it is ``https://github.com/<owner>/<repo>[.git]``, else raise."""
    if not repo_url or not repo_url.strip():
        raise InvalidRepoRefError("repo_url must not be blank")
    url = repo_url.strip()
    if len(url) > 512 or any(ord(c) < 0x20 or ord(c) == 0x7F for c in url) or "::" in url:
        raise InvalidRepoRefError("repo_url is malformed")
    parts = urlsplit(url)
    if parts.scheme.lower() != "https":
        raise InvalidRepoRefError("repo_url must use https")
    if parts.username or parts.password or parts.query or parts.fragment or parts.port:
        raise InvalidRepoRefError("repo_url must not carry userinfo, a port, a query or a fragment")
    if (parts.hostname or "").lower() not in ALLOWED_HOSTS:
        raise InvalidRepoRefError(f"repo_url host is not allowed: {parts.hostname!r}")
    path = parts.path[1:] if parts.path.startswith("/") else parts.path
    if path.endswith(".git"):
        path = path[:-4]
    if not _OWNER_REPO.fullmatch(path):
        raise InvalidRepoRefError("repo_url path must be owner/repo")
    return url


def validate_branch(branch: Optional[str]) -> Optional[str]:
    """None for blank (= default branch); the branch if it is a plain ref name; else raise."""
    if branch is None or not branch.strip():
        return None
    b = branch.strip()
    if (b.startswith("-") or ".." in b or "//" in b or "@{" in b
            or b.endswith((".lock", "/", ".")) or not _BRANCH.fullmatch(b)):
        raise InvalidRepoRefError(f"branch is not a valid ref name: {b!r}")
    return b


def workspace_root() -> str:
    return os.path.realpath(os.environ.get("GITORACLE_WORKSPACE_ROOT", DEFAULT_WORKSPACE_ROOT))


def is_within_workspace_root(path: str) -> bool:
    """True only for a path strictly beneath the workspace root (never the root itself)."""
    if not path:
        return False
    root = workspace_root()
    candidate = os.path.realpath(path)
    try:
        return candidate != root and os.path.commonpath([root, candidate]) == root
    except ValueError:
        return False
