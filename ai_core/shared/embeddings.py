"""Text embeddings for agent memory, from any OpenAI-compatible endpoint.

Memory used to POST to Ollama's native /api/embeddings at a URL derived from
LLM_BASE_URL. With any other provider (Groq has no embeddings at all) that URL
404'd, every memory was stored with an all-zero vector, and recall returned
arbitrary past entries as "similar".

Now embeddings are configured on their own, independently of the chat model:

  EMBEDDINGS_BASE_URL   full URL of an OpenAI-compatible embeddings endpoint, e.g.
                          http://localhost:11434/v1/embeddings   (Ollama, local)
                          http://localhost:1234/v1/embeddings    (LM Studio, local)
                          https://api.openai.com/v1/embeddings   (OpenAI)
                        or any other service that speaks the same API.
  EMBEDDINGS_MODEL      model name to request (e.g. all-minilm, nomic-embed-text,
                        text-embedding-3-small); omitted from the request if unset.
  EMBEDDINGS_API_KEY    bearer token, if the endpoint needs one.

Unset EMBEDDINGS_BASE_URL means memory is off: nothing is stored or recalled
(rather than storing meaningless vectors). No model ever runs inside the agent
process, so memory use stays flat; the only state is a small LRU cache of
recent embeddings (the fixer asks for the same bug description once per
attempt).

Storage is fixed at EMBEDDING_DIM (the pgvector column and Qdrant collection).
Vectors of another size are truncated and re-normalised, or zero-padded — exact
for Matryoshka-trained models (nomic-embed-text, text-embedding-3-*), usable
for others. Each stored vector is tagged with model_tag(), and recall only
compares vectors from the same model, so switching provider never mixes them.
"""
import logging
import math
import os
from collections import OrderedDict
from typing import Optional
from urllib.parse import urlparse

import httpx

logger = logging.getLogger(__name__)

EMBEDDING_DIM = 384
_CACHE_SIZE = 256
_cache: "OrderedDict[tuple[str, str], list[float]]" = OrderedDict()
_warned: set[str] = set()


def _warn_once(key: str, message: str) -> None:
    if key not in _warned:
        _warned.add(key)
        logger.warning(message)
    else:
        logger.debug(message)


def _config() -> tuple[str, str, str]:
    return (os.environ.get("EMBEDDINGS_BASE_URL", "").strip(),
            os.environ.get("EMBEDDINGS_MODEL", "").strip(),
            os.environ.get("EMBEDDINGS_API_KEY", "").strip())


def enabled() -> bool:
    return bool(_config()[0])


def model_tag() -> str:
    """Identifies which embedding space a vector belongs to: model @ host."""
    url, model, _ = _config()
    return f"{model or 'default'}@{urlparse(url).netloc or url}"


def fit_dimensions(vector: list[float], dim: int = EMBEDDING_DIM) -> list[float]:
    """Truncate (and re-normalise) or zero-pad a vector to `dim`."""
    if len(vector) == dim:
        return vector
    _warn_once("dim", f"Embedding model returns {len(vector)} dimensions; storing {dim} "
                      f"({'truncated' if len(vector) > dim else 'zero-padded'}). A model with "
                      f"{dim} dimensions, or a Matryoshka-trained one, gives the best recall.")
    if len(vector) < dim:
        return vector + [0.0] * (dim - len(vector))
    cut = vector[:dim]
    norm = math.sqrt(sum(x * x for x in cut)) or 1.0
    return [x / norm for x in cut]


def _parse(payload: dict) -> list[float]:
    # OpenAI-compatible: {"data": [{"embedding": [...]}]}. Ollama's native
    # shapes are accepted too, so a URL pointing at /api/embed(dings) works.
    if isinstance(payload.get("data"), list) and payload["data"]:
        return payload["data"][0]["embedding"]
    if isinstance(payload.get("embedding"), list):
        return payload["embedding"]
    if isinstance(payload.get("embeddings"), list) and payload["embeddings"]:
        return payload["embeddings"][0]
    raise ValueError(f"no embedding in response (keys: {sorted(payload)[:5]})")


async def embed(text: str) -> Optional[list[float]]:
    """The text's embedding at EMBEDDING_DIM, or None if embeddings are off or
    the endpoint failed (memory then degrades to "no matches", never to noise)."""
    url, model, key = _config()
    if not url:
        _warn_once("off", "Agent memory is off: set EMBEDDINGS_BASE_URL to an OpenAI-compatible "
                          "embeddings endpoint to enable it.")
        return None
    cache_key = (model_tag(), text)
    if cache_key in _cache:
        _cache.move_to_end(cache_key)
        return _cache[cache_key]

    body: dict = {"input": text}
    if model:
        body["model"] = model
    headers = {"Authorization": f"Bearer {key}"} if key else {}
    try:
        async with httpx.AsyncClient(timeout=10.0) as client:
            response = await client.post(url, json=body, headers=headers)
            response.raise_for_status()
            vector = fit_dimensions([float(x) for x in _parse(response.json())])
    except (httpx.HTTPError, ValueError, KeyError, TypeError) as e:
        _warn_once("fail", f"Embeddings request to {url} failed ({e}); memory recall finds nothing "
                           "until it works. Further failures are logged at debug.")
        return None

    _cache[cache_key] = vector
    if len(_cache) > _CACHE_SIZE:
        _cache.popitem(last=False)
    return vector
