"""
Containment for file paths that originate from an LLM or from attacker-supplied
text.

Why this exists
---------------
The Fixer read every path in ``plan.affected_files`` straight into the prompt::

    full_path = os.path.join(local_src_path, file_path)
    with open(full_path, "r") as f:
        source_context += f.read()

``os.path.join`` **discards the prefix entirely** when the second argument is
absolute, and it does nothing about ``..``::

    >>> os.path.join("/tmp/repo", "/etc/passwd")
    '/etc/passwd'
    >>> os.path.join("/tmp/repo", "../../../../etc/passwd")
    '/tmp/repo/../../../../etc/passwd'

So a single entry in that list read an arbitrary host file and placed its
contents in the prompt sent to the LLM provider — an exfiltration primitive
whose output leaves the machine by design. The list is not human-authored: it
is planner LLM output, and when the plan carries no files the Fixer falls back
to a regex over ``human_instructions``/``bug_description``, which for a webhook
job is attacker-supplied text. That regex accepts ``.`` and ``/``, so
``a/../../../../etc/nginx/nginx.conf`` matches it whole.

The same list then becomes ``authorized_files`` — the allowlist Guardrails
validates the patch against — so an unsafe entry does not merely get read, it
gets *authorized*.

What "safe" means here
----------------------
A path is usable only if, after full resolution, it is still inside the
checkout root, and is not on the deny list. Resolution uses ``os.path.realpath``
so that a **symlink inside the repo pointing out of it** is caught too: a
attacker-controlled repository can contain ``config -> /etc/passwd``, which no
amount of string inspection of the *relative* path would reveal.

``os.path.commonpath`` is used rather than ``str.startswith`` because the latter
treats ``/tmp/repo-secrets`` as being inside ``/tmp/repo``.
"""

import fnmatch
import logging
import os
from typing import Iterable, List, Optional, Tuple

logger = logging.getLogger(__name__)


class UnsafePathError(ValueError):
    """A path escaped the checkout root or is explicitly denied."""


# Never readable or writable by an agent, regardless of any allowlist — an
# LLM-generated plan is not a trust anchor for touching these. A compromised or
# prompt-injected completion must not be able to authorize its own privilege
# escalation (rewriting the CI workflow that later runs with real credentials,
# reading a deploy key) merely by naming the path in its own output.
#
# This is the canonical copy. agents/guardrails/patch_scanner.py imports it from
# here rather than keeping a second list, so the boundary the Fixer enforces
# when reading and the boundary Guardrails enforces when validating cannot drift
# apart.
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

# Additional read-side denials. These matter specifically because the Fixer
# reads file contents into an LLM prompt: a private key or credentials file is
# far more damaging to *read* (and ship to a third-party API) than to have
# listed in a diff, so the read boundary is deliberately wider than the
# patch-validation one.
DENIED_READ_GLOBS = DENIED_GLOBS + [
    "*.key", "**/*.key",
    "*.p12", "**/*.p12",
    "*.pfx", "**/*.pfx",
    "id_rsa", "**/id_rsa",
    "id_dsa", "**/id_dsa",
    "id_ecdsa", "**/id_ecdsa",
    "id_ed25519", "**/id_ed25519",
    ".npmrc", "**/.npmrc",
    ".netrc", "**/.netrc",
    ".pypirc", "**/.pypirc",
    ".aws/**", "**/.aws/**",
    ".ssh/**", "**/.ssh/**",
    "credentials", "**/credentials",
    "*.keystore", "**/*.keystore",
    "*.jks", "**/*.jks",
]


def _has_traversal(path: str) -> bool:
    # Split on both separators: on POSIX, "a\\..\\b" is a single filename, but
    # normalising the check costs nothing and avoids a platform-dependent hole.
    parts = path.replace("\\", "/").split("/")
    return ".." in parts


def is_denied_path(path: str, globs: Optional[List[str]] = None) -> bool:
    """
    True if ``path`` is absolute, home-relative, contains traversal, or matches
    a denied glob. Operates on the *relative* string, so it is the cheap
    first-pass check; it cannot see through symlinks — that is what
    :func:`resolve_within` is for.
    """
    if not path or not path.strip():
        return True
    if path.startswith("/") or path.startswith("~") or _has_traversal(path):
        return True
    # A Windows-style absolute path ("C:\\...") is not meaningful here and is
    # never a legitimate path within a checked-out repository.
    if len(path) > 1 and path[1] == ":":
        return True
    return any(fnmatch.fnmatch(path, pattern) for pattern in (globs or DENIED_GLOBS))


def resolve_within(root: str, relative_path: str, *, for_read: bool = True) -> str:
    """
    Resolve ``relative_path`` against ``root`` and return an absolute path that
    is guaranteed to be inside ``root``.

    :raises UnsafePathError: if the path is absolute, traverses upward, matches
        a denied glob, or resolves (through symlinks) outside ``root``.
    """
    globs = DENIED_READ_GLOBS if for_read else DENIED_GLOBS

    if is_denied_path(relative_path, globs):
        raise UnsafePathError(f"Denied or non-relative path: {relative_path!r}")

    real_root = os.path.realpath(root)
    candidate = os.path.realpath(os.path.join(real_root, relative_path))

    # commonpath, not startswith: "/tmp/repo-secrets".startswith("/tmp/repo")
    # is True and would let a sibling directory pass as contained.
    try:
        if os.path.commonpath([real_root, candidate]) != real_root:
            raise UnsafePathError(
                f"Path escapes the checkout root: {relative_path!r} -> {candidate!r}"
            )
    except ValueError as exc:
        # Raised when the two paths are on different drives or one is relative;
        # either way containment cannot be established, so refuse.
        raise UnsafePathError(f"Cannot contain path {relative_path!r}: {exc}") from exc

    # realpath() resolved any symlink, so the containment check above already
    # covers a link pointing out of the tree. This second check catches the
    # remaining case: the resolved target is inside the root but the *resolved*
    # relative form is itself denied (e.g. a symlink into .git/).
    resolved_relative = os.path.relpath(candidate, real_root)
    if is_denied_path(resolved_relative, globs):
        raise UnsafePathError(
            f"Path resolves to a denied location: {relative_path!r} -> {resolved_relative!r}"
        )

    return candidate


def safe_read_text(root: str, relative_path: str, *, max_bytes: int = 1_000_000) -> str:
    """
    Read a file that is guaranteed to live inside ``root``.

    ``max_bytes`` bounds what a single file can contribute to an LLM prompt.
    Without it, naming a large file is a cheap way to blow the context window
    or the token budget.
    """
    full_path = resolve_within(root, relative_path, for_read=True)
    with open(full_path, "r", errors="replace") as handle:
        return handle.read(max_bytes)


def partition_safe(root: str, candidates: Iterable[str]) -> Tuple[List[str], List[Tuple[str, str]]]:
    """
    Split ``candidates`` into those safely inside ``root`` and those rejected.

    Returns ``(safe, rejected)`` where each rejected entry is
    ``(path, reason)``. Callers get to log every rejection rather than having
    the list silently shrink — a plan that named an unsafe file is worth
    surfacing, not quietly ignoring.
    """
    safe: List[str] = []
    rejected: List[Tuple[str, str]] = []
    for candidate in candidates:
        try:
            resolve_within(root, candidate, for_read=True)
            safe.append(candidate)
        except UnsafePathError as exc:
            rejected.append((candidate, str(exc)))
    return safe, rejected
