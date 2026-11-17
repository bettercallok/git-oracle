"""
Give an agent its own copy of a job's repository.

The orchestrator clones each job into ``<workspace root>/<job id>`` and sends that
path in the event. On one machine the agents read it directly. In a cluster
each agent runs on its own disk, where the path doesn't exist, so the agent
clones the repository there itself and checks out the job's ``base_commit``,
the commit the orchestrator recorded as HEAD: every agent then sees exactly
the code the job started from. Git is the shared store; no volume is.
"""

import logging
import os
import re
import shutil
import subprocess
import time
import uuid
from typing import Optional

import git

from shared.clone_auth import fetch_clone_token, git_auth_env
from shared.repo_refs import InvalidRepoRefError, is_within_workspace_root, validate_branch, validate_repo_url, workspace_root

logger = logging.getLogger(__name__)

_SHA = re.compile(r"[0-9a-f]{7,40}")
_GIT_TIMEOUT_S = 120


def _git(path: str, *args: str, env: Optional[dict[str, str]] = None) -> subprocess.CompletedProcess:
    return subprocess.run(["git", "-C", path, *args], capture_output=True, text=True, timeout=_GIT_TIMEOUT_S,
                          env={**os.environ, **env} if env else None)


def head_commit(path: str) -> Optional[str]:
    try:
        out = _git(path, "rev-parse", "HEAD")
    except (OSError, subprocess.TimeoutExpired):
        return None
    return out.stdout.strip() if out.returncode == 0 else None


def checkout_commit(path: str, sha: str, auth: Optional[dict[str, str]] = None) -> bool:
    """Detach ``path`` at ``sha``, fetching it if the clone doesn't have it. False if it can't."""
    if not _SHA.fullmatch(sha or ""):
        return False
    try:
        if _git(path, "checkout", "--quiet", "--detach", sha).returncode == 0:
            return True
        _git(path, "fetch", "--quiet", "origin", sha, env=auth)
        return _git(path, "checkout", "--quiet", "--detach", sha).returncode == 0
    except (OSError, subprocess.TimeoutExpired):
        return False


def ensure_checkout(repo_path: str, repo_url: str, branch: Optional[str] = None,
                    base_commit: Optional[str] = None, job_id: Optional[str] = None) -> str:
    """Return a directory holding the job's repository, cloning it if this host lacks it.

    An existing checkout at ``repo_path`` is used as it is: on one machine that
    is the orchestrator's own clone, already at the base commit. Otherwise the
    repository is cloned into ``repo_path`` (which must be under the workspace
    root) and detached at ``base_commit``. Returns ``""`` when there is nothing
    to read and nothing it may clone, so callers degrade as they did before.
    """
    if repo_path and os.path.isdir(os.path.join(repo_path, ".git")):
        return repo_path
    if not repo_path or not is_within_workspace_root(repo_path):
        logger.warning("No checkout at %r and it is outside the workspace root; not cloning", repo_path)
        return ""
    try:
        url = validate_repo_url(repo_url)
        ref = validate_branch(branch)
    except InvalidRepoRefError as e:
        logger.warning("Not cloning into %s: %s", repo_path, e)
        return ""

    _sweep_old_workspaces()
    # Clone beside the target and rename, so a crash or a concurrent clone never
    # leaves a half-written checkout where the next reader expects a whole one.
    tmp = f"{repo_path.rstrip('/')}.cloning-{uuid.uuid4().hex[:8]}"
    kwargs: dict = {"branch": ref} if ref else {}
    # A private repository needs the orchestrator's read token (shared/clone_auth).
    auth = git_auth_env(fetch_clone_token(job_id))
    try:
        try:
            git.Repo.clone_from(url, tmp, env=auth, **kwargs)
        except git.GitCommandError:
            if not auth:
                raise
            # The token may not cover this repository; a public one clones without it.
            shutil.rmtree(tmp, ignore_errors=True)
            auth = {}
            git.Repo.clone_from(url, tmp, **kwargs)
        if base_commit and not checkout_commit(tmp, base_commit, auth):
            logger.warning("Could not check out base commit %s in %s; reading %s instead",
                           base_commit, repo_path, ref or "the default branch")
        try:
            os.rename(tmp, repo_path)
        except OSError:
            if not os.path.isdir(os.path.join(repo_path, ".git")):
                raise
            # Another handler on this host finished first; use its copy.
            shutil.rmtree(tmp, ignore_errors=True)
        logger.info("Cloned %s at %s into %s", url, base_commit or ref or "HEAD", repo_path)
        return repo_path
    except (git.GitCommandError, OSError) as e:
        shutil.rmtree(tmp, ignore_errors=True)
        logger.warning("Could not clone %s into %s: %s", url, repo_path, e)
        return ""


def _sweep_old_workspaces() -> None:
    """Delete checkouts older than GITORACLE_WORKSPACE_MAX_AGE_HOURS, when that is set.

    Off by default: locally the orchestrator manages its own workspaces. In a
    cluster each agent's checkouts sit on its own scratch volume, and nothing
    else removes them.
    """
    try:
        hours = float(os.environ.get("GITORACLE_WORKSPACE_MAX_AGE_HOURS", "0"))
    except ValueError:
        return
    if hours <= 0:
        return
    root = workspace_root()
    cutoff = time.time() - hours * 3600
    try:
        entries = list(os.scandir(root))
    except OSError:
        return
    for entry in entries:
        try:
            if entry.is_dir(follow_symlinks=False) and entry.stat().st_mtime < cutoff \
                    and is_within_workspace_root(entry.path):
                shutil.rmtree(entry.path, ignore_errors=True)
        except OSError:
            continue
