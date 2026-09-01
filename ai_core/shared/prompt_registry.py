import os
import httpx
import logging
from typing import Optional, Tuple

logger = logging.getLogger(__name__)

PROMPT_REGISTRY_URL = os.environ.get("PROMPT_REGISTRY_URL", "http://localhost:9005")


async def fetch_prompt_versioned(
    agent_name: str, fallback: str, prompt_key: str = "system"
) -> Tuple[str, Optional[int]]:
    """Fetch an agent's base system prompt *and* the version number that produced it.

    The version is what makes per-prompt performance measurable: without it there is
    no way to attribute a job's outcome or token spend back to the specific prompt
    revision that generated it.

    Returns (prompt_text, version). Version is None when the registry is unavailable
    or has no active version — in that case the fallback text is used, and the work
    is deliberately left unattributed rather than being credited to a version that
    did not actually produce it.
    """
    try:
        async with httpx.AsyncClient(timeout=5.0) as client:
            response = await client.get(
                f"{PROMPT_REGISTRY_URL}/prompts/{agent_name}/{prompt_key}/active",
                headers={"X-Internal-Token": os.environ.get("GITORACLE_INTERNAL_TOKEN", "")}
            )
            response.raise_for_status()
            payload = response.json()
            return payload["content"], payload.get("version")
    except Exception as e:
        logger.warning(f"Failed to fetch prompt for agent '{agent_name}' from registry: {e}")
        return fallback, None


async def fetch_prompt(agent_name: str, fallback: str, prompt_key: str = "system") -> str:
    """Backwards-compatible wrapper returning only the prompt text."""
    content, _version = await fetch_prompt_versioned(agent_name, fallback, prompt_key)
    return content


ORCHESTRATOR_URL = os.environ.get("ORCHESTRATOR_URL", "http://localhost:8083")


async def report_prompt_version(job_id: Optional[str], agent_name: str, version: Optional[int]) -> None:
    """Attribute this job to the prompt version that actually produced its output.

    Silently skipped when the version is unknown (registry unreachable, so the
    hardcoded fallback prompt was used) — recording a guess would corrupt the very
    per-version statistics this exists to make trustworthy.
    """
    if not job_id or version is None:
        return
    try:
        async with httpx.AsyncClient(timeout=5.0) as client:
            await client.post(
                f"{ORCHESTRATOR_URL}/api/v1/jobs/{job_id}/prompt-version",
                json={"agentName": agent_name, "version": version},
                headers={"X-Internal-Token": os.environ.get("GITORACLE_INTERNAL_TOKEN", "")}
            )
    except Exception as e:
        logger.warning(f"Failed to report prompt version for job {job_id}: {e}")
