"""Which organisation a Kafka job message belongs to.

The investigator, planner and fixer used to hardcode the default tenant when a
job arrived over Kafka, so every organisation's agent memory was filed under
one id and two organisations working on the same repository recalled each
other's fixes. The orchestrator now sends ``tenant_id`` with every dispatch and
each agent passes it on to the next hop.
"""
import uuid
from typing import Any

DEFAULT_TENANT_ID = "00000000-0000-0000-0000-000000000000"


def tenant_id_of(payload: Any) -> str:
    """The payload's tenant id, or the default tenant when it is absent or not a UUID."""
    value = payload.get("tenant_id") if isinstance(payload, dict) else None
    if not isinstance(value, str) or not value.strip():
        return DEFAULT_TENANT_ID
    try:
        return str(uuid.UUID(value.strip()))
    except ValueError:
        return DEFAULT_TENANT_ID
