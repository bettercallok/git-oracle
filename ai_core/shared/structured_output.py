import os
import time
import json
import asyncio
import random
import httpx
import logging
from datetime import datetime, timezone
from typing import Type, TypeVar, Dict, List, Optional
from pydantic import BaseModel, ValidationError

from shared.metrics import LLM_LATENCY, LLM_REQUESTS, LLM_TOKENS, canonical_agent
from dotenv import load_dotenv, find_dotenv
from tenacity import retry, stop_after_attempt, wait_exponential, retry_if_exception_type

# Load .env so we pick up LANGFUSE_PUBLIC_KEY, LANGFUSE_SECRET_KEY, etc.
load_dotenv(find_dotenv())

logger = logging.getLogger(__name__)

T = TypeVar("T", bound=BaseModel)

class LLMUnavailableError(Exception):
    """Raised when the primary LLM (and fallback, if configured) is unreachable."""
    pass


class LLMRequestError(Exception):
    """
    The provider answered, but refused the request (a 4xx other than 429).

    Carries the provider's own explanation. This used to surface as httpx's
    "Client error '400 Bad Request' for url ... For more information check:
    https://developer.mozilla.org/..." — the status code and a generic MDN
    link, with the body that says *what* was wrong thrown away.
    """

    def __init__(self, status_code: int, provider_message: str):
        self.status_code = status_code
        self.provider_message = provider_message
        super().__init__(f"LLM provider rejected the request ({status_code}): {provider_message}")


# Ceiling for the one automatic retry after a reply was cut off mid-JSON.
LLM_MAX_TOKENS_CEILING = int(os.environ.get("LLM_MAX_TOKENS_CEILING", "8192"))


def _provider_error(response: httpx.Response) -> tuple[str, str]:
    """(code, message) from an OpenAI-style error body; best effort, never raises."""
    try:
        err = response.json().get("error", {})
        if isinstance(err, dict):
            return str(err.get("code") or err.get("type") or ""), str(err.get("message") or "")
        return "", str(err)
    except Exception:  # noqa: BLE001 — a non-JSON error body is still worth reporting as text
        return "", response.text[:500]


def _is_json_validation_failure(response: httpx.Response) -> bool:
    """
    Groq (and some OpenAI-compatible servers) validate JSON-mode output
    themselves and answer 400 "json_validate_failed" when the model's reply
    isn't valid JSON — most often because max_tokens cut it off mid-object.
    """
    if response.status_code != 400:
        return False
    code, message = _provider_error(response)
    return code == "json_validate_failed" or "failed to generate json" in message.lower()


def _recover_failed_generation(response: httpx.Response) -> Optional[str]:
    """
    The JSON object inside Groq's rejected ``failed_generation``, when there
    is a complete one. Models often wrap valid JSON in a ```json fence or a
    sentence, which JSON mode rejects even though the object itself is fine.
    """
    try:
        text = response.json().get("error", {}).get("failed_generation")
    except Exception:  # noqa: BLE001 — no recoverable body
        return None
    if not isinstance(text, str):
        return None
    start, end = text.find("{"), text.rfind("}")
    if start < 0 or end <= start:
        return None
    candidate = text[start:end + 1]
    try:
        json.loads(candidate)
    except ValueError:
        return None
    return candidate

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
    # Empty counts as unset. Setting the keys to "" is how a deployment without
    # Langfuse (CI) turns tracing off; passing "" through still built a client
    # that failed — and flushed synchronously — on every LLM call.
    if not os.environ.get("LANGFUSE_PUBLIC_KEY") or not os.environ.get("LANGFUSE_SECRET_KEY"):
        raise RuntimeError("LANGFUSE_PUBLIC_KEY / LANGFUSE_SECRET_KEY not set")
    _lf = Langfuse(
        public_key=os.environ["LANGFUSE_PUBLIC_KEY"],
        secret_key=os.environ["LANGFUSE_SECRET_KEY"],
        # LANGFUSE_HOST when it's a full URL (a Langfuse elsewhere: a container
        # network, a hosted instance). .env.example has always set it to the bare
        # hostname "localhost" for other uses, so a value without a scheme keeps
        # the old http://localhost:<LANGFUSE_PORT> instead of breaking tracing.
        host=(os.environ.get("LANGFUSE_HOST", "") if os.environ.get("LANGFUSE_HOST", "").startswith(("http://", "https://"))
              else f"http://localhost:{os.environ.get('LANGFUSE_PORT', '3002')}"),
    )
    logger.info("Langfuse observability initialised ✓")
except Exception as _e:  # noqa: BLE001 — tracing is optional; any init failure disables it
    _lf = None
    logger.warning(f"Langfuse not available — tracing disabled: {_e}")


# ─── Helpers ──────────────────────────────────────────────────────────────────

async def _report_tokens(job_id: str, tokens_used: int, agent_name: str) -> None:
    """Report token consumption to the Orchestrator budget endpoint (non-blocking)."""
    try:
        async with httpx.AsyncClient(timeout=5.0) as client:
            # BudgetController is mapped at /api/v1/budget, not /budget. This
            # posted to the wrong path and 404'd on every single LLM call —
            # silently, because the failure is only a warning. Confirmed live:
            # token_budget_used was 0 across all 29 jobs in the database, so every
            # token-based figure in the system was structurally always zero.
            await client.post(
                f"{ORCHESTRATOR_URL}/api/v1/budget/{job_id}/record",
                json={"tokensUsed": tokens_used, "agentName": agent_name},
                headers={"X-Internal-Token": os.environ.get("GITORACLE_INTERNAL_TOKEN", "")}
            )
    except Exception as e:  # noqa: BLE001 — token reporting is best-effort
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
    model:      Optional[str] = None,
    level:      str = "DEFAULT",
    status_message: Optional[str] = None,
) -> None:
    """
    Send a single generation event to Langfuse. Fails silently.

    One trace per job (trace_id = job_id), one generation per LLM call, and the
    generation is named after the agent that made it. It used to be the other
    way round: every generation was called "llm_structured" and each call
    renamed the shared trace to "<agent>_llm_call", so a job's trace carried
    whichever agent happened to write last and nothing said which call was
    whose. The dashboard's trace viewer groups and labels calls by this name.
    """
    if _lf is None:
        return
    try:
        trace = _lf.trace(
            id=trace_id,
            # Stable for a job's trace; a call outside any job (the commit
            # analyst's chat) gets its own trace named after the agent.
            name="gitoracle-job" if trace_id else f"{agent_name}_llm_call",
        )
        trace.generation(
            name=agent_name,
            metadata={"agent": agent_name},
            model=model or LLM_MODEL_NAME,
            level=level,
            status_message=status_message,
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
    except Exception as e:  # noqa: BLE001 — tracing must never fail an LLM call
        logger.warning(f"Langfuse tracing error (non-fatal): {e}")


@retry(
    stop=stop_after_attempt(3),
    wait=wait_exponential(multiplier=1, min=2, max=10),
    retry=retry_if_exception_type((httpx.ConnectError, httpx.TimeoutException, httpx.ReadError)),
    reraise=True
)
async def _execute_llm_request(client: httpx.AsyncClient, base_url: str, payload: dict, headers: Optional[dict] = None) -> httpx.Response:
    """Execute LLM request with exponential backoff for transient network errors."""
    logger.debug(f"Attempting LLM call to {base_url}")
    response = await client.post(base_url, json=payload, headers=headers)
    if response.status_code >= 500:
        response.raise_for_status()
    return response


# A 429 used to be returned like any other 4xx and raised by the caller's
# raise_for_status, so a free-tier provider's per-minute limit failed the agent
# outright and escalated the job. Confirmed live on job 9e09ccf6: several
# concurrent pipeline runs tripped Groq's limit and every planner and fixer call
# in the next minute failed within a second, with no attempt to wait it out.
LLM_RATE_LIMIT_RETRIES = int(os.environ.get("LLM_RATE_LIMIT_RETRIES", "4"))
LLM_RATE_LIMIT_MAX_WAIT_SECONDS = float(os.environ.get("LLM_RATE_LIMIT_MAX_WAIT_SECONDS", "60"))


def _retry_after_seconds(response: httpx.Response) -> Optional[float]:
    """The provider's Retry-After, in seconds, when it sends a numeric one."""
    value = response.headers.get("retry-after")
    if not value:
        return None
    try:
        return max(0.0, float(value))
    except ValueError:
        return None  # HTTP-date form: fall back to our own backoff


async def _execute_with_rate_limit_backoff(client: httpx.AsyncClient, base_url: str, payload: dict,
                                           headers: Optional[dict] = None) -> httpx.Response:
    """_execute_llm_request, waiting out 429s (Retry-After if given, else exponential).

    Still rate-limited after the last retry raises HTTPStatusError, so the
    caller can try the fallback model before giving up.
    """
    for attempt in range(LLM_RATE_LIMIT_RETRIES + 1):
        response = await _execute_llm_request(client, base_url, payload, headers=headers)
        if response.status_code != 429:
            return response
        if attempt == LLM_RATE_LIMIT_RETRIES:
            break
        delay = _retry_after_seconds(response)
        if delay is None:
            delay = 2.0 ** (attempt + 1) + random.uniform(0, 1)
        delay = min(delay, LLM_RATE_LIMIT_MAX_WAIT_SECONDS)
        logger.warning(f"LLM rate-limited (429) by {base_url} — waiting {delay:.1f}s "
                       f"(retry {attempt + 1} of {LLM_RATE_LIMIT_RETRIES})")
        await asyncio.sleep(delay)
    response.raise_for_status()
    return response


# ─── Core Function ────────────────────────────────────────────────────────────

async def _llm_structured(
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

    # Inject schema into the system prompt since we are using json_object mode
    schema_instruction = f"\n\nYou MUST return ONLY valid JSON matching this schema:\n{json.dumps(json_schema)}"
    
    # Create a new list to avoid mutating the original
    processed_messages = list(messages)
    if processed_messages and processed_messages[0]["role"] == "system":
        processed_messages[0] = {"role": "system", "content": processed_messages[0]["content"] + schema_instruction}
    else:
        processed_messages.insert(0, {"role": "system", "content": schema_instruction})

    payload = {
        "model":       LLM_MODEL_NAME,   # Required by Ollama; ignored by llama.cpp
        "messages":    processed_messages,
        "max_tokens":  max_tokens,
        "temperature": temperature,
        "stream":      False,
        "response_format": {
            "type": "json_object"
        }
    }

    start_time = datetime.now(timezone.utc)

    headers = {}
    api_key = os.environ.get("OPENAI_API_KEY")
    if api_key:
        headers["Authorization"] = f"Bearer {api_key}"

    answered_by: str = base_url   # whichever endpoint actually answers
    async with httpx.AsyncClient(timeout=120.0) as client:
        try:
            # 1. Primary Attempt
            response = await _execute_with_rate_limit_backoff(client, base_url, payload, headers=headers)
        except Exception as primary_e:
            logger.warning(f"Primary LLM ({base_url}) failed: {primary_e}")
            if LLM_FALLBACK_BASE_URL and LLM_FALLBACK_MODEL_NAME:
                logger.info(f"Attempting fallback LLM: {LLM_FALLBACK_BASE_URL}")
                payload["model"] = LLM_FALLBACK_MODEL_NAME
                try:
                    # 2. Fallback Attempt
                    response = await _execute_with_rate_limit_backoff(client, LLM_FALLBACK_BASE_URL, payload, headers=headers)
                    answered_by = LLM_FALLBACK_BASE_URL
                except Exception as fallback_e:
                    logger.error(f"Fallback LLM also failed: {fallback_e}")
                    raise LLMUnavailableError("Both primary and fallback LLM servers are unavailable.") from fallback_e
            else:
                raise LLMUnavailableError(f"Primary LLM server is unavailable and no fallback configured: {primary_e}") from primary_e

        # 3. Process Response
        recovered: Optional[str] = None
        if _is_json_validation_failure(response):
            recovered = _recover_failed_generation(response)
            if recovered is None and max_tokens < LLM_MAX_TOKENS_CEILING:
                # Cut off mid-JSON: one more try with room to finish, on the
                # endpoint that just answered (the fallback, if the primary was down).
                max_tokens = min(max_tokens * 2, LLM_MAX_TOKENS_CEILING)
                payload["max_tokens"] = max_tokens
                logger.warning(
                    f"{agent_name}: provider rejected a reply that was not valid JSON; "
                    f"retrying once with max_tokens={max_tokens}")
                response = await _execute_with_rate_limit_backoff(client, answered_by, payload, headers=headers)
                if _is_json_validation_failure(response):
                    recovered = _recover_failed_generation(response)

        if recovered is None and 400 <= response.status_code < 500:
            code, message = _provider_error(response)
            if _is_json_validation_failure(response):
                message = (f"{message} The model's reply was not valid JSON even with "
                           f"max_tokens={max_tokens} — try a narrower question, "
                           f"or a model that follows JSON mode more reliably.")
            raise LLMRequestError(response.status_code, f"{message}{f' [{code}]' if code else ''}".strip()[:1000])

        try:
            if recovered is None:
                response.raise_for_status()

            end_time    = datetime.now(timezone.utc)
            if recovered is not None:
                logger.info(f"{agent_name}: recovered a valid JSON object from the provider's rejected reply")
                raw_content = recovered
                usage: Dict = {}
            else:
                data        = response.json()
                raw_content = data["choices"][0]["message"]["content"]
                usage       = data.get("usage", {})
            tokens_used = usage.get("total_tokens", 0)
            _agent = canonical_agent(agent_name)
            LLM_TOKENS.labels(agent=_agent, kind="prompt").inc(usage.get("prompt_tokens", 0) or 0)
            LLM_TOKENS.labels(agent=_agent, kind="completion").inc(usage.get("completion_tokens", 0) or 0)

            # 4. Report tokens to the Java Orchestrator budget tracker
            if job_id and tokens_used:
                await _report_tokens(job_id, tokens_used, agent_name)

            # 5. Validate output against the Pydantic schema, then
            # 6. send the full call to Langfuse (non-blocking, silent failure)
            #    — marked ERROR when the reply did not fit the schema, so a
            #    failed attempt is visible in the trace instead of looking
            #    like any other call.
            validation_error: Optional[ValidationError] = None
            try:
                parsed = output_schema.model_validate_json(raw_content)
            except ValidationError as ve:
                validation_error = ve
            _trace_to_langfuse(
                trace_id=trace_id or job_id,
                agent_name=agent_name,
                messages=messages,
                raw_output=raw_content,
                usage=usage,
                start_time=start_time,
                end_time=end_time,
                model=str(payload["model"]),   # the fallback model when that is what answered
                level="ERROR" if validation_error else "DEFAULT",
                status_message=(f"Reply did not match {output_schema.__name__}: {validation_error}"[:1000]
                                if validation_error else None),
            )
            if validation_error:
                raise validation_error
            return parsed

        except httpx.HTTPError as e:
            logger.error(f"HTTP error parsing LLM response: {e}")
            raise
        except Exception as e:
            logger.error(f"Failed to parse LLM structured output: {e}")
            raise


async def llm_structured(
    messages: List[Dict[str, str]],
    output_schema: Type[T],
    max_tokens: int = 2048,
    temperature: float = 0.2,
    base_url: Optional[str] = None,  # None: LLM_BASE_URL from the environment, read per call
    job_id:     Optional[str] = None,
    agent_name: str = "unknown",
    trace_id:   Optional[str] = None,
) -> T:
    """
    _llm_structured, counted for Prometheus (shared/metrics.py): one
    gitoracle_llm_requests_total per call by outcome, and its latency.

    outcome: ok · schema_error (the reply didn't fit the schema) ·
    provider_error (the provider refused or errored) · unavailable (primary and
    fallback both unreachable).
    """
    agent = canonical_agent(agent_name)
    outcome = "provider_error"
    started = time.monotonic()
    try:
        # Read when called, not frozen at import: the default used to be the
        # module constant, fixed by whichever import of this module came first.
        url = base_url or os.environ.get("LLM_BASE_URL") or LLM_BASE_URL
        result = await _llm_structured(messages, output_schema, max_tokens=max_tokens, temperature=temperature,
                                       base_url=url, job_id=job_id, agent_name=agent_name, trace_id=trace_id)
        outcome = "ok"
        return result
    except ValidationError:
        outcome = "schema_error"
        raise
    except LLMUnavailableError:
        outcome = "unavailable"
        raise
    finally:
        LLM_REQUESTS.labels(agent=agent, outcome=outcome).inc()
        LLM_LATENCY.labels(agent=agent).observe(time.monotonic() - started)
