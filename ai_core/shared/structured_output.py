import os
import time
import json
import httpx
import logging
from datetime import datetime, timezone
from typing import Type, TypeVar, Dict, List, Optional
from pydantic import BaseModel
from dotenv import load_dotenv, find_dotenv
from tenacity import retry, stop_after_attempt, wait_exponential, retry_if_exception_type

# Load .env so we pick up LANGFUSE_PUBLIC_KEY, LANGFUSE_SECRET_KEY, etc.
load_dotenv(find_dotenv())

logger = logging.getLogger(__name__)

T = TypeVar("T", bound=BaseModel)

class LLMUnavailableError(Exception):
    """Raised when the primary LLM (and fallback, if configured) is unreachable."""
    pass

ORCHESTRATOR_URL = os.environ.get("ORCHESTRATOR_URL", "http://localhost:8083")

# ─── LLM Backend Configuration ────────────────────────────────────────────────
# LLM_BASE_URL controls which inference backend is used.
# Swap this env var to change backends without touching any agent code:
#   Mac dev (Ollama native Metal GPU): http://localhost:11434/v1/chat/completions
#   Linux+NVIDIA (llama.cpp Docker):   http://localhost:8090/v1/chat/completions
#   Cloud (Together.ai, Groq, etc.):   https://api.together.xyz/v1/chat/completions
LLM_BASE_URL   = os.environ.get("LLM_BASE_URL", "http://localhost:11434/v1/chat/completions")
LLM_MODEL_NAME = os.environ.get("LLM_MODEL_NAME", "qwen2.5-coder:7b-instruct-q4_K_M")

# Fallback Configuration
LLM_FALLBACK_BASE_URL = os.environ.get("LLM_FALLBACK_BASE_URL")
LLM_FALLBACK_MODEL_NAME = os.environ.get("LLM_FALLBACK_MODEL_NAME")

# ─── Langfuse Client (singleton) ──────────────────────────────────────────────
# Initialised once at module-load. Fails silently if env vars are missing so
# that missing observability config never crashes a production agent.
try:
    from langfuse import Langfuse
    _lf = Langfuse(
        public_key=os.environ["LANGFUSE_PUBLIC_KEY"],
        secret_key=os.environ["LANGFUSE_SECRET_KEY"],
        host=f"http://localhost:{os.environ.get('LANGFUSE_PORT', '3002')}",
    )
    logger.info("Langfuse observability initialised ✓")
except Exception as _e:
    _lf = None
    logger.warning(f"Langfuse not available — tracing disabled: {_e}")


# ─── Helpers ──────────────────────────────────────────────────────────────────

async def _report_tokens(job_id: str, tokens_used: int, agent_name: str) -> None:
    """Report token consumption to the Orchestrator budget endpoint (non-blocking)."""
    try:
        async with httpx.AsyncClient(timeout=5.0) as client:
            await client.post(
                f"{ORCHESTRATOR_URL}/budget/{job_id}/record",
                json={"tokensUsed": tokens_used, "agentName": agent_name}
            )
    except Exception as e:
        logger.warning(f"Failed to report tokens to Orchestrator: {e}")


def _trace_to_langfuse(
    *,
    trace_id:   Optional[str],
    agent_name: str,
    messages:   List[Dict],
    raw_output: str,
    usage:      Dict,
    start_time: datetime,
    end_time:   datetime,
) -> None:
    """Send a single generation event to Langfuse. Fails silently."""
    if _lf is None:
        return
    try:
        trace = _lf.trace(
            id=trace_id,
            name=f"{agent_name}_llm_call",
        )
        trace.generation(
            name="llm_structured",
            model=LLM_MODEL_NAME,
            input=messages,
            output=raw_output,
            usage={
                "input":  usage.get("prompt_tokens", 0),
                "output": usage.get("completion_tokens", 0),
                "total":  usage.get("total_tokens", 0),
            },
            start_time=start_time,
            end_time=end_time,
        )
        _lf.flush()
    except Exception as e:
        logger.warning(f"Langfuse tracing error (non-fatal): {e}")


@retry(
    stop=stop_after_attempt(3),
    wait=wait_exponential(multiplier=1, min=2, max=10),
    retry=retry_if_exception_type((httpx.ConnectError, httpx.TimeoutException, httpx.ReadError)),
    reraise=True
)
async def _execute_llm_request(client: httpx.AsyncClient, base_url: str, payload: dict) -> httpx.Response:
    """Execute LLM request with exponential backoff for transient network errors."""
    logger.debug(f"Attempting LLM call to {base_url}")
    response = await client.post(base_url, json=payload)
    if response.status_code >= 500:
        response.raise_for_status()
    return response


# ─── Core Function ────────────────────────────────────────────────────────────

async def llm_structured(
    messages: List[Dict[str, str]],
    output_schema: Type[T],
    max_tokens: int = 2048,
    temperature: float = 0.2,
    base_url: str = LLM_BASE_URL,   # defaults to env var — swap backend by changing .env
    job_id:     Optional[str] = None,
    agent_name: str = "unknown",
    trace_id:   Optional[str] = None,
) -> T:
    """
    Sends a structured chat-completion request to the local llama.cpp server.

    Every call is automatically:
      - Constrained to the Pydantic schema via json_schema response_format
      - Traced to Langfuse (prompt + response + tokens + latency)
      - Reported to the Orchestrator token budget tracker (when job_id provided)
    """
    json_schema = output_schema.model_json_schema()

    payload = {
        "model":       LLM_MODEL_NAME,   # Required by Ollama; ignored by llama.cpp
        "messages":    messages,
        "max_tokens":  max_tokens,
        "temperature": temperature,
        "stream":      False,
        "response_format": {
            "type": "json_schema",
            "json_schema": {
                "name":   output_schema.__name__,
                "strict": True,
                "schema": json_schema,
            }
        }
    }

    start_time = datetime.now(timezone.utc)

    async with httpx.AsyncClient(timeout=120.0) as client:
        try:
            # 1. Primary Attempt
            response = await _execute_llm_request(client, base_url, payload)
            used_model = LLM_MODEL_NAME
        except Exception as primary_e:
            logger.warning(f"Primary LLM ({base_url}) failed: {primary_e}")
            if LLM_FALLBACK_BASE_URL and LLM_FALLBACK_MODEL_NAME:
                logger.info(f"Attempting fallback LLM: {LLM_FALLBACK_BASE_URL}")
                payload["model"] = LLM_FALLBACK_MODEL_NAME
                try:
                    # 2. Fallback Attempt
                    response = await _execute_llm_request(client, LLM_FALLBACK_BASE_URL, payload)
                    used_model = LLM_FALLBACK_MODEL_NAME
                except Exception as fallback_e:
                    logger.error(f"Fallback LLM also failed: {fallback_e}")
                    raise LLMUnavailableError("Both primary and fallback LLM servers are unavailable.") from fallback_e
            else:
                raise LLMUnavailableError(f"Primary LLM server is unavailable and no fallback configured: {primary_e}") from primary_e

        # 3. Process Response
        try:
            response.raise_for_status()
            
            end_time    = datetime.now(timezone.utc)
            data        = response.json()
            raw_content = data["choices"][0]["message"]["content"]
            usage       = data.get("usage", {})
            tokens_used = usage.get("total_tokens", 0)

            # 4. Report tokens to the Java Orchestrator budget tracker
            if job_id and tokens_used:
                await _report_tokens(job_id, tokens_used, agent_name)

            # 5. Send full trace to Langfuse (non-blocking, silent failure)
            _trace_to_langfuse(
                trace_id=trace_id or job_id,
                agent_name=agent_name,
                messages=messages,
                raw_output=raw_content,
                usage=usage,
                start_time=start_time,
                end_time=end_time,
            )

            # 6. Validate output against the Pydantic schema
            return output_schema.model_validate_json(raw_content)

        except httpx.HTTPError as e:
            logger.error(f"HTTP error parsing LLM response: {e}")
            raise
        except Exception as e:
            logger.error(f"Failed to parse LLM structured output: {e}")
            raise
