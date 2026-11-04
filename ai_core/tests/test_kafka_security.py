"""Agents authenticate to Kafka's host listener (security audit D4).

The listener used to accept any local process, which could then publish to
job.events.fix and bypass every HTTP-layer check. Clients now use SASL/PLAIN
when KAFKA_SECURITY_PROTOCOL says so, and refuse to start half-configured.
(Verified live against a SASL broker: right password delivers, no or wrong
credentials are refused.)"""
import os
import sys

import pytest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from shared.config import check_startup_config  # noqa: E402
from shared.kafka_security import kafka_security_kwargs  # noqa: E402


@pytest.fixture(autouse=True)
def clean(monkeypatch):
    for name in ("KAFKA_SECURITY_PROTOCOL", "KAFKA_SASL_USERNAME", "KAFKA_SASL_PASSWORD"):
        monkeypatch.delenv(name, raising=False)
    monkeypatch.setenv("GITORACLE_INTERNAL_TOKEN", "tok")


def test_plaintext_by_default_for_the_internal_listener():
    assert kafka_security_kwargs() == {}


def test_sasl_settings_for_the_host_listener(monkeypatch):
    monkeypatch.setenv("KAFKA_SECURITY_PROTOCOL", "sasl_plaintext")
    monkeypatch.setenv("KAFKA_SASL_PASSWORD", "pw")
    assert kafka_security_kwargs() == {
        "security_protocol": "SASL_PLAINTEXT", "sasl_mechanism": "PLAIN",
        "sasl_plain_username": "gitoracle", "sasl_plain_password": "pw",
    }


@pytest.mark.parametrize("env, message", [
    ({"KAFKA_SECURITY_PROTOCOL": "SASL_PLAINTEXT"}, "needs KAFKA_SASL_PASSWORD"),
    ({"KAFKA_SECURITY_PROTOCOL": "SSL"}, "Unsupported KAFKA_SECURITY_PROTOCOL"),
])
def test_a_half_configured_agent_refuses_to_start(monkeypatch, env, message):
    for k, v in env.items():
        monkeypatch.setenv(k, v)
    with pytest.raises(SystemExit, match=message):
        check_startup_config("guardrails", needs_llm=False)
