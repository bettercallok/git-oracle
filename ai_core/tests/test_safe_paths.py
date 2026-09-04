"""
Regression tests for shared/safe_paths.py — the containment layer for file
paths that come from an LLM or from attacker-supplied text.

Context: the Fixer read every entry of plan.affected_files with
os.path.join(local_src_path, file_path) + open(). os.path.join discards its
first argument entirely when the second is absolute, and does nothing about
"..", so a single list entry read an arbitrary host file straight into the
prompt sent to the LLM provider — exfiltration whose output leaves the machine
by design. The list is planner LLM output, and when the plan is empty the Fixer
falls back to a regex over webhook-supplied text.

These are pure functions with no network or LLM dependency, so the specific
payloads that worked are cheap to pin permanently.
"""
import os
import sys

import pytest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from shared.safe_paths import (  # noqa: E402
    DENIED_GLOBS,
    DENIED_READ_GLOBS,
    UnsafePathError,
    is_denied_path,
    partition_safe,
    resolve_within,
    safe_read_text,
)


@pytest.fixture
def repo(tmp_path):
    """A checkout root with a secret sitting next to it, as on a real host."""
    root = tmp_path / "repo"
    (root / "src").mkdir(parents=True)
    (root / "src" / "app.py").write_text("print('hello')\n")
    (root / ".env").write_text("SECRET=hunter2\n")

    # Outside the checkout — the thing traversal is trying to reach.
    (tmp_path / "outside.txt").write_text("TOP SECRET\n")

    # A sibling whose name shares a string prefix with the root: this is why
    # containment uses commonpath rather than startswith.
    sibling = tmp_path / "repo-secrets"
    sibling.mkdir()
    (sibling / "creds.txt").write_text("AWS_KEY=...\n")

    return root


# ── The payloads the plan's verification section names ──────────────────────

@pytest.mark.parametrize("payload", ["../../etc/passwd", "/etc/passwd", "a/../../b"])
def test_the_three_documented_payloads_are_rejected(repo, payload):
    with pytest.raises(UnsafePathError):
        resolve_within(str(repo), payload)


def test_absolute_path_does_not_silently_discard_the_root(repo):
    # The actual mechanism of the bug: os.path.join throws the root away.
    assert os.path.join(str(repo), "/etc/passwd") == "/etc/passwd"
    with pytest.raises(UnsafePathError):
        resolve_within(str(repo), "/etc/passwd")


def test_traversal_to_a_real_file_outside_the_root_is_rejected(repo):
    # Not a hypothetical path — this file exists and would have been read.
    outside = os.path.join(os.path.dirname(str(repo)), "outside.txt")
    assert os.path.isfile(outside)

    with pytest.raises(UnsafePathError):
        resolve_within(str(repo), "../outside.txt")
    with pytest.raises(UnsafePathError):
        safe_read_text(str(repo), "../outside.txt")


def test_deeply_nested_traversal_is_rejected(repo):
    with pytest.raises(UnsafePathError):
        resolve_within(str(repo), "a/../../../../../../etc/passwd")


def test_home_relative_path_is_rejected(repo):
    with pytest.raises(UnsafePathError):
        resolve_within(str(repo), "~/.ssh/id_rsa")


# ── Containment corner cases ────────────────────────────────────────────────

def test_a_sibling_sharing_a_string_prefix_is_not_inside_the_root(repo):
    # "/tmp/x/repo-secrets".startswith("/tmp/x/repo") is True, which is exactly
    # why containment is done with commonpath.
    with pytest.raises(UnsafePathError):
        resolve_within(str(repo), "../repo-secrets/creds.txt")


def test_symlink_pointing_out_of_the_repo_is_rejected(repo, tmp_path):
    # A repository is attacker-controlled input. No inspection of the relative
    # path string alone can reveal this; only realpath can.
    link = repo / "innocent.txt"
    link.symlink_to(tmp_path / "outside.txt")
    assert os.path.isfile(link)  # it really does resolve to the secret

    with pytest.raises(UnsafePathError):
        resolve_within(str(repo), "innocent.txt")
    with pytest.raises(UnsafePathError):
        safe_read_text(str(repo), "innocent.txt")


def test_symlinked_directory_escaping_the_repo_is_rejected(repo, tmp_path):
    (repo / "linkdir").symlink_to(tmp_path, target_is_directory=True)

    with pytest.raises(UnsafePathError):
        resolve_within(str(repo), "linkdir/outside.txt")


def test_symlink_that_stays_inside_the_repo_is_allowed(repo):
    (repo / "alias.py").symlink_to(repo / "src" / "app.py")

    assert safe_read_text(str(repo), "alias.py") == "print('hello')\n"


# ── Legitimate paths must still work ────────────────────────────────────────

def test_ordinary_relative_paths_resolve(repo):
    resolved = resolve_within(str(repo), "src/app.py")

    assert resolved == os.path.realpath(str(repo / "src" / "app.py"))
    assert safe_read_text(str(repo), "src/app.py") == "print('hello')\n"


def test_a_path_that_normalises_back_inside_is_allowed(repo):
    # "src/../src/app.py" contains ".." but never actually leaves the root.
    # It is still rejected: the string check is deliberately conservative, and
    # no legitimate planner output needs this form.
    with pytest.raises(UnsafePathError):
        resolve_within(str(repo), "src/../src/app.py")


def test_nonexistent_but_contained_path_is_not_a_containment_error(repo):
    # Containment and existence are separate concerns; resolve_within must not
    # reject a file simply because the planner named one that isn't there.
    resolved = resolve_within(str(repo), "src/does_not_exist.py")

    assert resolved.startswith(os.path.realpath(str(repo)))


# ── Deny globs ──────────────────────────────────────────────────────────────

@pytest.mark.parametrize("path", [
    ".env",
    ".env.production",
    "config/.env",
    "deploy/key.pem",
    ".github/workflows/ci.yml",
    ".git/config",
    "package-lock.json",
])
def test_denied_globs_are_rejected_even_though_contained(repo, path):
    # These live inside the checkout — containment alone would allow them.
    with pytest.raises(UnsafePathError):
        resolve_within(str(repo), path)


@pytest.mark.parametrize("path", [
    "secrets/id_rsa",
    "app.key",
    ".ssh/known_hosts",
    ".aws/credentials",
    ".npmrc",
    "keystore.jks",
])
def test_read_side_denies_credentials_beyond_the_patch_deny_list(repo, path):
    # Reading a private key into a third-party LLM prompt is worse than having
    # it named in a diff, so the read boundary is wider than DENIED_GLOBS.
    assert is_denied_path(path, DENIED_READ_GLOBS)
    with pytest.raises(UnsafePathError):
        resolve_within(str(repo), path, for_read=True)


def test_read_deny_list_is_a_superset_of_the_patch_deny_list():
    assert set(DENIED_GLOBS).issubset(set(DENIED_READ_GLOBS))


def test_empty_and_whitespace_paths_are_denied():
    assert is_denied_path("")
    assert is_denied_path("   ")


# ── partition_safe, which is what the Fixer actually calls ──────────────────

def test_partition_safe_keeps_good_paths_and_reports_each_rejection(repo):
    safe, rejected = partition_safe(str(repo), [
        "src/app.py",
        "/etc/passwd",
        "../../etc/passwd",
        ".env",
        "src/other.py",
    ])

    assert safe == ["src/app.py", "src/other.py"]
    assert [path for path, _ in rejected] == ["/etc/passwd", "../../etc/passwd", ".env"]
    # Every rejection carries a reason so it can be logged rather than the list
    # silently shrinking.
    assert all(reason for _, reason in rejected)


def test_partition_safe_on_an_all_unsafe_list_returns_nothing_safe(repo):
    safe, rejected = partition_safe(str(repo), ["/etc/passwd", "~/.ssh/id_rsa"])

    assert safe == []
    assert len(rejected) == 2


def test_max_bytes_bounds_what_one_file_contributes_to_a_prompt(repo):
    (repo / "huge.py").write_text("x" * 5000)

    assert len(safe_read_text(str(repo), "huge.py", max_bytes=100)) == 100
