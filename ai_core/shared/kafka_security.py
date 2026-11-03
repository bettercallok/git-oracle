"""Connection security for the agents' Kafka clients (security audit D4).

The broker's host listener (localhost:9092) was PLAINTEXT with no
authentication, so any local process could publish to job.events.fix and
friends and bypass every HTTP-layer check. It now requires SASL/PLAIN. Host
processes set KAFKA_SECURITY_PROTOCOL=SASL_PLAINTEXT (start_local.sh exports
it) plus KAFKA_SASL_USERNAME / KAFKA_SASL_PASSWORD from .env; containers on the
internal Docker listener keep the PLAINTEXT default — the same contract as the
Java services' spring.kafka.properties.
"""
import os


def kafka_security_kwargs() -> dict:
    """Extra AIOKafkaConsumer/AIOKafkaProducer kwargs for the configured protocol."""
    protocol = os.getenv("KAFKA_SECURITY_PROTOCOL", "PLAINTEXT").strip().upper() or "PLAINTEXT"
    if protocol == "PLAINTEXT":
        return {}
    if protocol != "SASL_PLAINTEXT":
        raise ValueError(f"Unsupported KAFKA_SECURITY_PROTOCOL {protocol!r} (PLAINTEXT or SASL_PLAINTEXT)")
    password = os.getenv("KAFKA_SASL_PASSWORD", "")
    if not password:
        raise ValueError("KAFKA_SECURITY_PROTOCOL=SASL_PLAINTEXT needs KAFKA_SASL_PASSWORD")
    return {
        "security_protocol": "SASL_PLAINTEXT",
        "sasl_mechanism": "PLAIN",
        "sasl_plain_username": os.getenv("KAFKA_SASL_USERNAME", "gitoracle") or "gitoracle",
        "sasl_plain_password": password,
    }
