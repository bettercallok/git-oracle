"""Progress notes for a job's timeline (orchestrator: POST /api/v1/jobs/{id}/events).

The orchestrator records every state change itself; this is for what happens
*inside* a state — "attempt 2 started", "search block not found in UserService.java"
— which otherwise only exists in the agent's own log file. Before it, a job stuck
in FIXING gave no hint whether the fixer was on its first attempt or its third.

Fails soft, like _report_tokens in structured_output.py: a timeline note is never
worth failing or slowing down the actual work.
"""
import logging
import os
from typing import Any, Dict, Optional

import httpx

logger = logging.getLogger(__name__)

ORCHESTRATOR_URL = os.environ.get("ORCHESTRATOR_URL", "http://localhost:8083")

PROGRESS = "AGENT_PROGRESS"
ERROR = "ERROR"


async def report_event(
    job_id: Optional[str],
    actor: str,
    message: str,
    *,
    type: str = PROGRESS,
    details: Optional[Dict[str, Any]] = None,
) -> None:
    # Ad-hoc HTTP calls to an agent's endpoint carry placeholder ids such as
    # "debug-job"; those have no timeline to write to.
    if not job_id or job_id in ("unknown", "unknown-job", "debug-job"):
        return
    try:
        async with httpx.AsyncClient(timeout=3.0) as client:
            resp = await client.post(
                f"{ORCHESTRATOR_URL}/api/v1/jobs/{job_id}/events",
                json={"type": type, "actor": actor, "message": message[:4000], "details": details or {}},
                headers={"X-Internal-Token": os.environ.get("GITORACLE_INTERNAL_TOKEN", "")},
            )
            if resp.status_code >= 400:
                logger.warning(f"Timeline event for job {job_id} rejected: HTTP {resp.status_code}")
    except Exception as e:  # noqa: BLE001 — timeline notes are best-effort by design (see module docstring)
        logger.warning(f"Failed to report timeline event for job {job_id}: {e}")
