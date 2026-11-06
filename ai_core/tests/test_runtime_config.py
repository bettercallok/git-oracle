"""
The System status page shows where code is being sent, so this must report the
real environment, and must never let a credential out through a URL or a key.
"""
import json
import os
import sys

from fastapi import FastAPI
from fastapi.testclient import TestClient

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from shared.health import add_health_route  # noqa: E402
from shared.runtime_config import runtime_config  # noqa: E402


def _clear(monkeypatch):
    for name in ("LLM_FALLBACK_BASE_URL", "LLM_FALLBACK_MODEL_NAME", "EMBEDDINGS_BASE_URL", "EMBEDDINGS_MODEL",
                 "KAFKA_BOOTSTRAP_SERVERS", "KAFKA_SECURITY_PROTOCOL", "OPENAI_API_KEY"):
        monkeypatch.delenv(name, raising=False)
    monkeypatch.setenv("LLM_BASE_URL", "https://api.groq.com/openai/v1/chat/completions")
    monkeypatch.setenv("LLM_MODEL_NAME", "some-model")


def test_a_cloud_llm_is_reported_as_external_by_host_only(monkeypatch):
    _clear(monkeypatch)

    assert runtime_config()["llm"] == {
        "endpoint": {"host": "api.groq.com", "local": False},
        "model": "some-model",
        "sendsApiKey": False,
    }


def test_local_llm_addresses_count_as_local(monkeypatch):
    _clear(monkeypatch)
    for url, host in [
        ("http://localhost:11434/v1/chat/completions", "localhost:11434"),
        ("http://127.0.0.1:8090/v1/chat/completions", "127.0.0.1:8090"),
        ("http://192.168.1.20:1234/v1/chat/completions", "192.168.1.20:1234"),
        ("http://host.docker.internal:11434/v1/chat/completions", "host.docker.internal:11434"),
    ]:
        monkeypatch.setenv("LLM_BASE_URL", url)
        assert runtime_config()["llm"]["endpoint"] == {"host": host, "local": True}


def test_no_secret_can_leave_through_a_url_or_key(monkeypatch):
    _clear(monkeypatch)
    monkeypatch.setenv("LLM_BASE_URL", "https://user:url-password@llm.example/v1/chat/completions?token=query-secret")
    monkeypatch.setenv("OPENAI_API_KEY", "key-secret")
    monkeypatch.setenv("QDRANT_API_KEY", "qdrant-secret")
    monkeypatch.setenv("KAFKA_SASL_PASSWORD", "kafka-secret")

    config = runtime_config()
    text = json.dumps(config)

    for secret in ("url-password", "query-secret", "key-secret", "qdrant-secret", "kafka-secret", "user"):
        assert secret not in text
    assert config["llm"]["sendsApiKey"] is True


def test_a_fallback_only_counts_when_it_would_really_be_used(monkeypatch):
    _clear(monkeypatch)
    monkeypatch.setenv("LLM_FALLBACK_BASE_URL", "https://cloud.example/v1/chat/completions")
    assert runtime_config()["fallback"] is None

    monkeypatch.setenv("LLM_FALLBACK_MODEL_NAME", "cloud-model")
    assert runtime_config()["fallback"] == {"endpoint": {"host": "cloud.example", "local": False}, "model": "cloud-model"}


def test_memory_shows_as_off_without_embeddings(monkeypatch):
    _clear(monkeypatch)
    assert runtime_config()["embeddings"] is None


def test_the_route_needs_the_internal_token_even_without_a_global_check(monkeypatch):
    _clear(monkeypatch)
    monkeypatch.setenv("GITORACLE_INTERNAL_TOKEN", "secret")
    app = FastAPI()
    add_health_route(app, "fixer")
    client = TestClient(app)

    assert client.get("/runtime-config").status_code == 401
    answer = client.get("/runtime-config", headers={"X-Internal-Token": "secret"})
    assert answer.status_code == 200
    assert answer.json()["llm"]["model"] == "some-model"
