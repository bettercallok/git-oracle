import json
import httpx
import logging
from typing import Type, TypeVar, Any, Dict, List, Optional
from pydantic import BaseModel

logger = logging.getLogger(__name__)

T = TypeVar("T", bound=BaseModel)

ORCHESTRATOR_URL = "http://localhost:8083"

async def _report_tokens(job_id: str, tokens_used: int, agent_name: str) -> None:
    """
    Non-blocking: report token consumption to the Orchestrator budget endpoint.
    Fails silently so a network hiccup never breaks the AI pipeline.
    """
    try:
        async with httpx.AsyncClient(timeout=5.0) as client:
            await client.post(
                f"{ORCHESTRATOR_URL}/budget/{job_id}/record",
                json={"tokensUsed": tokens_used, "agentName": agent_name}
            )
    except Exception as e:
        logger.warning(f"Failed to report tokens to Orchestrator: {e}")


async def llm_structured(
    messages: List[Dict[str, str]],
    output_schema: Type[T],
    max_tokens: int = 2048,
    temperature: float = 0.2,
    base_url: str = "http://localhost:8090/v1/chat/completions",
    job_id: Optional[str] = None,
    agent_name: str = "unknown"
) -> T:
    """
    Sends a chat completion request to the local llama.cpp server,
    enforcing the output to strictly match the provided Pydantic schema.

    If job_id is provided, token usage is automatically reported
    to the Orchestrator's Token Budget Manager after each call.
    """
    
    # Modern llama.cpp server natively supports OpenAI's json_schema response_format
    json_schema = output_schema.model_json_schema()
    
    payload = {
        "messages": messages,
        "max_tokens": max_tokens,
        "temperature": temperature,
        "stream": False,
        "response_format": {
            "type": "json_schema",
            "json_schema": {
                "name": output_schema.__name__,
                "schema": json_schema
            }
        }
    }
    
    async with httpx.AsyncClient(timeout=1200.0) as client:
        try:
            response = await client.post(base_url, json=payload)
            response.raise_for_status()
            
            data = response.json()
            raw_content = data["choices"][0]["message"]["content"]

            # Report token usage to Orchestrator if job_id is provided
            usage = data.get("usage", {})
            tokens_used = usage.get("total_tokens", 0)
            if job_id and tokens_used:
                await _report_tokens(job_id, tokens_used, agent_name)
            
            # Pydantic will rigorously validate the returned JSON string
            result = output_schema.model_validate_json(raw_content)
            return result
            
        except httpx.HTTPError as e:
            logger.error(f"HTTP Error calling LLM Server: {e}")
            raise
        except Exception as e:
            logger.error(f"Failed to parse LLM structured output: {e}")
            raise
