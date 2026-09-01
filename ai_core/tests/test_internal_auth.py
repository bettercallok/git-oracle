"""
Regression tests for shared/internal_auth.py's require_internal_token.

Context: every one of GitOracle's 13 services used to bind 0.0.0.0 with no
auth of its own — anything that could reach a service's port directly (not
through the api-gateway) could call it. This FastAPI dependency, applied at
the app level on every one of the 7 Python agents, requires a shared-secret
X-Internal-Token header matching GITORACLE_INTERNAL_TOKEN. These tests pin
both the fail-closed behavior (an unconfigured token must refuse everything,
not silently allow it) and the actual accept/reject decision.
"""
import os
import sys

import pytest
from fastapi import HTTPException

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from shared.internal_auth import require_internal_token  # noqa: E402


@pytest.fixture(autouse=True)
def clean_env(monkeypatch):
    monkeypatch.delenv("GITORACLE_INTERNAL_TOKEN", raising=False)


def test_fails_closed_when_token_not_configured():
    """An unset server-side token must refuse everything, not skip the check
    — the same posture GITORACLE_API_KEY takes on the gateway."""
    with pytest.raises(HTTPException) as exc_info:
        require_internal_token(x_internal_token="anything")
    assert exc_info.value.status_code == 401
    assert "GITORACLE_INTERNAL_TOKEN" in exc_info.value.detail


def test_rejects_missing_header(monkeypatch):
    monkeypatch.setenv("GITORACLE_INTERNAL_TOKEN", "real-secret")
    with pytest.raises(HTTPException) as exc_info:
        require_internal_token(x_internal_token=None)
    assert exc_info.value.status_code == 401


def test_rejects_wrong_token(monkeypatch):
    monkeypatch.setenv("GITORACLE_INTERNAL_TOKEN", "real-secret")
    with pytest.raises(HTTPException) as exc_info:
        require_internal_token(x_internal_token="wrong-secret")
    assert exc_info.value.status_code == 401


def test_accepts_correct_token(monkeypatch):
    monkeypatch.setenv("GITORACLE_INTERNAL_TOKEN", "real-secret")
    # Does not raise.
    require_internal_token(x_internal_token="real-secret")
