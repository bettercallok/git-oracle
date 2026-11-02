"""
Requires a shared-secret X-Internal-Token header on every request to an
internal agent. Every one of GitOracle's 13 services used to bind 0.0.0.0
with no auth of its own — the api-gateway's X-API-Key check only ever
protected traffic that went through the gateway, but every service was also
directly reachable on its own port, so the gateway was advisory rather than
an actual boundary. Applying `require_internal_token` as an app-level
dependency closes that for the 7 Python agents, mirroring
InternalAuthFilter in each of the Java services.

Nothing external calls a Python agent directly today (every caller is
another GitOracle service — the orchestrator, the fixer, another agent), so
this is applied to every route except EXEMPT_PATHS: /health reports liveness
only, and a supervisor or probe must be able to reach it without the secret
(the same carve-out the Java filters make for /actuator and /health).
"""
import hmac
import os

from fastapi import Header, HTTPException, Request

EXEMPT_PATHS = frozenset({"/health"})


def require_internal_token(
    request: Request = None,  # type: ignore[assignment]  # injected by FastAPI; None in direct unit-test calls
    x_internal_token: str | None = Header(default=None),
) -> None:
    if request is not None and request.url.path in EXEMPT_PATHS:
        return
    configured = os.environ.get("GITORACLE_INTERNAL_TOKEN", "")
    # Fails CLOSED: an unconfigured token means "refuse everything", not
    # "skip the check" — the same posture the Java services' fail-closed
    # checks take for an unset server-side secret.
    if not configured:
        raise HTTPException(status_code=401, detail="Server misconfiguration: GITORACLE_INTERNAL_TOKEN is not set.")
    if not x_internal_token or not hmac.compare_digest(x_internal_token, configured):
        raise HTTPException(status_code=401, detail="Invalid or missing X-Internal-Token header.")
