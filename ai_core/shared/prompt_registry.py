import os
import httpx
import logging

logger = logging.getLogger(__name__)

PROMPT_REGISTRY_URL = os.environ.get("PROMPT_REGISTRY_URL", "http://localhost:9005")


async def fetch_prompt(agent_name: str, fallback: str, prompt_key: str = "system") -> str:
    """Fetch an agent's base system prompt from the versioned prompt registry service.

    Falls back to the given default if the registry is unavailable or has no active
    version for this agent/key.
    """
    try:
        async with httpx.AsyncClient(timeout=5.0) as client:
            response = await client.get(f"{PROMPT_REGISTRY_URL}/prompts/{agent_name}/{prompt_key}")
            response.raise_for_status()
            return response.json()
    except Exception as e:
        logger.warning(f"Failed to fetch prompt for agent '{agent_name}' from registry: {e}")
        return fallback
