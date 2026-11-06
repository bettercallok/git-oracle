"""What this agent is really configured to use, for the System status page.

The dashboard used to show editable boxes with made-up values. This reports the
environment the running process actually reads, so an operator can see where
code is being sent. It returns hosts only: never a path, query, credential or
key, because URLs can carry secrets and a key must never leave the process.
"""
import ipaddress
import os
from typing import Optional
from urllib.parse import urlsplit

_LOCAL_NAMES = {"localhost", "host.docker.internal"}


def _endpoint(url: Optional[str]) -> Optional[dict]:
    """{"host": "api.groq.com", "local": False} for a URL, or None if unset or unusable."""
    if not url or not url.strip():
        return None
    try:
        parts = urlsplit(url.strip())
        host = parts.hostname
        port = parts.port
    except ValueError:
        return None
    if not host:
        return None
    return {"host": f"{host}:{port}" if port else host, "local": _is_local(host)}


def _is_local(host: str) -> bool:
    if host in _LOCAL_NAMES or host.endswith(".local"):
        return True
    try:
        address = ipaddress.ip_address(host)
    except ValueError:
        return False
    return address.is_loopback or address.is_private


def runtime_config() -> dict:
    env = os.environ.get
    return {
        "llm": {
            "endpoint": _endpoint(env("LLM_BASE_URL")),
            "model": env("LLM_MODEL_NAME") or None,
            # Whether a key is attached to LLM calls, wherever they go. Never the key.
            "sendsApiKey": bool(env("OPENAI_API_KEY")),
        },
        # Only used when both are set; see shared/structured_output.py.
        "fallback": {
            "endpoint": _endpoint(env("LLM_FALLBACK_BASE_URL")),
            "model": env("LLM_FALLBACK_MODEL_NAME") or None,
        } if env("LLM_FALLBACK_BASE_URL") and env("LLM_FALLBACK_MODEL_NAME") else None,
        "embeddings": {
            "endpoint": _endpoint(env("EMBEDDINGS_BASE_URL")),
            "model": env("EMBEDDINGS_MODEL") or None,
        } if env("EMBEDDINGS_BASE_URL") else None,
        "kafka": {
            "host": env("KAFKA_BOOTSTRAP_SERVERS") or f"{env('KAFKA_HOST', '127.0.0.1')}:{env('KAFKA_PORT', '9092')}",
            "authenticated": env("KAFKA_SECURITY_PROTOCOL", "PLAINTEXT") != "PLAINTEXT",
        },
        "qdrant": {"host": f"{env('QDRANT_HOST', 'localhost')}:{env('QDRANT_HTTP_PORT', '6333')}"},
    }
