"""
Credentials for cloning a job's repository, so private repositories work.

The orchestrator mints a token that can only read that one repository
(``POST /api/v1/jobs/{id}/clone-token``, internal token required) and keeps it
off Kafka, where payloads are logged and copied to the DLQ. Git gets it through
environment configuration: not in the URL, the clone's .git/config, the
process list or git's error messages.

No token (204, an unreachable orchestrator, no GitHub App) means an anonymous
clone, which is what every clone was before and still works for a public repo.
"""

import base64
import logging
import os
import re
from typing import Optional

import httpx

logger = logging.getLogger(__name__)

_JOB_ID = re.compile(r"[0-9a-fA-F-]{36}")


def fetch_clone_token(job_id: Optional[str]) -> Optional[str]:
    """The job's repository read token from the orchestrator, or None."""
    if not job_id or not _JOB_ID.fullmatch(job_id):
        return None
    base = os.environ.get("ORCHESTRATOR_URL", "http://localhost:8083").rstrip("/")
    try:
        response = httpx.post(
            f"{base}/api/v1/jobs/{job_id}/clone-token",
            headers={"X-Internal-Token": os.environ.get("GITORACLE_INTERNAL_TOKEN", "")},
            timeout=10.0,
        )
    except httpx.HTTPError as e:
        logger.info("No clone token for job %s: %s", job_id, e)
        return None
    if response.status_code != 200:
        return None
    try:
        token = response.json().get("token")
    except ValueError:
        return None
    return token if isinstance(token, str) and token else None


def git_auth_env(token: Optional[str]) -> dict[str, str]:
    """Environment that makes git send ``token`` to github.com (git 2.31+). Empty for no token."""
    if not token:
        return {}
    basic = base64.b64encode(f"x-access-token:{token}".encode()).decode()
    return {
        "GIT_CONFIG_COUNT": "1",
        "GIT_CONFIG_KEY_0": "http.https://github.com/.extraheader",
        "GIT_CONFIG_VALUE_0": f"AUTHORIZATION: basic {basic}",
        "GIT_TERMINAL_PROMPT": "0",
    }
