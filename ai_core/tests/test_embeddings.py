"""Agent memory's embeddings: any OpenAI-compatible endpoint, off when unset.

Memory used to call Ollama's native API at a URL derived from LLM_BASE_URL;
with Groq that 404'd and every memory was stored as an all-zero vector, so
recall returned arbitrary entries as "similar". These pin: no endpoint → no
storage and no recall (never noise); the common response shapes parse; vectors
always fit the 384-d column; repeated text isn't re-requested; and memories
are keyed by repository, not by a job's workspace."""
import asyncio
import math
import os
import sys

import httpx
import pytest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from shared import embeddings, memory  # noqa: E402

URL = "http://embed.test/v1/embeddings"


class _Resp:
    def __init__(self, payload, status=200):
        self._payload, self.status_code = payload, status

    def json(self):
        return self._payload

    def raise_for_status(self):
        if self.status_code >= 400:
            raise httpx.HTTPStatusError("boom", request=httpx.Request("POST", URL),
                                        response=httpx.Response(self.status_code))


@pytest.fixture(autouse=True)
def clean(monkeypatch):
    embeddings._cache.clear()
    embeddings._warned.clear()
    for name in ("EMBEDDINGS_BASE_URL", "EMBEDDINGS_MODEL", "EMBEDDINGS_API_KEY"):
        monkeypatch.delenv(name, raising=False)


@pytest.fixture
def endpoint(monkeypatch):
    """Configure an endpoint and answer POSTs with `reply[0]`; record requests."""
    monkeypatch.setenv("EMBEDDINGS_BASE_URL", URL)
    monkeypatch.setenv("EMBEDDINGS_MODEL", "all-minilm")
    calls, reply = [], [{"data": [{"embedding": [0.5] * 384}]}]

    async def post(self, url, json=None, headers=None, **kwargs):
        calls.append({"url": url, "json": json, "headers": headers or {}})
        return reply[0] if isinstance(reply[0], _Resp) else _Resp(reply[0])

    monkeypatch.setattr(httpx.AsyncClient, "post", post)
    return calls, reply


def test_no_endpoint_means_no_embedding_and_no_request(monkeypatch):
    async def post(*args, **kwargs):
        raise AssertionError("must not call anything")

    monkeypatch.setattr(httpx.AsyncClient, "post", post)
    assert asyncio.run(embeddings.embed("x")) is None
    assert embeddings.enabled() is False


def test_openai_compatible_request_and_response(endpoint, monkeypatch):
    calls, _ = endpoint
    monkeypatch.setenv("EMBEDDINGS_API_KEY", "sk-test")

    vector = asyncio.run(embeddings.embed("hello"))

    assert len(vector) == 384
    assert calls[0]["url"] == URL
    assert calls[0]["json"] == {"input": "hello", "model": "all-minilm"}
    assert calls[0]["headers"]["Authorization"] == "Bearer sk-test"


@pytest.mark.parametrize("payload", [
    {"embedding": [0.1] * 384},              # Ollama /api/embeddings
    {"embeddings": [[0.1] * 384]},           # Ollama /api/embed
])
def test_ollama_native_shapes_parse_too(endpoint, payload):
    _, reply = endpoint
    reply[0] = payload
    assert len(asyncio.run(embeddings.embed("x"))) == 384


def test_larger_vectors_are_truncated_and_renormalised():
    out = embeddings.fit_dimensions([1.0] * 768)

    assert len(out) == 384
    assert math.isclose(math.sqrt(sum(x * x for x in out)), 1.0, rel_tol=1e-9)


def test_smaller_vectors_are_zero_padded():
    out = embeddings.fit_dimensions([1.0] * 100)
    assert len(out) == 384 and out[100:] == [0.0] * 284


def test_repeated_text_is_served_from_the_cache(endpoint):
    calls, _ = endpoint
    asyncio.run(embeddings.embed("same bug"))
    asyncio.run(embeddings.embed("same bug"))
    assert len(calls) == 1


def test_a_failing_endpoint_gives_none_not_a_zero_vector(endpoint):
    _, reply = endpoint
    reply[0] = _Resp({}, status=404)
    assert asyncio.run(embeddings.embed("x")) is None


def test_the_model_tag_names_model_and_host(endpoint):
    assert embeddings.model_tag() == "all-minilm@embed.test"


# ── memory ──────────────────────────────────────────────────────────────────

@pytest.mark.parametrize("given", [
    "https://github.com/Owner/Repo",
    "https://github.com/owner/repo.git",
    "https://github.com/owner/repo/",
    "https://x-access-token:abc@github.com/owner/repo.git",
    "git@github.com:owner/repo.git",
])
def test_every_form_of_a_repo_url_gives_one_key(given):
    assert memory.repo_key(given) == "github.com/owner/repo"


def test_memory_stores_and_recalls_nothing_without_embeddings(monkeypatch):
    async def no_db():
        raise AssertionError("must not touch the database")

    monkeypatch.setattr(memory, "get_db_connection", no_db)
    m = memory.AgentMemory()
    tenant = "00000000-0000-0000-0000-000000000000"

    assert asyncio.run(m.remember(tenant, "https://github.com/o/r", "episodic", "fixed it")) == ""
    assert asyncio.run(m.recall(tenant, "https://github.com/o/r", "bug", "episodic")) == []
