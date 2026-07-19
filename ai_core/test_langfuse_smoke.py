"""
Fast smoke test for Layer 3e — Langfuse LLM Observability.
Uses a tiny output schema + 150 max_tokens so it completes in ~10 seconds.
After running, open http://localhost:3002 → Traces to see the entry.
"""
import asyncio
import sys
import os

sys.path.append(os.path.dirname(__file__))

from pydantic import BaseModel
from shared.structured_output import llm_structured

class QuickCheck(BaseModel):
    status: str     # "ok" or "error"
    message: str    # one short sentence

async def main():
    print("🔭 Layer 3e — Langfuse tracing smoke test")
    print("─" * 45)

    messages = [
        {"role": "system", "content": "You are a health-check agent. Respond concisely."},
        {"role": "user",   "content": "Reply with status=ok and a one-sentence confirmation."}
    ]

    result = await llm_structured(
        messages=messages,
        output_schema=QuickCheck,
        max_tokens=150,       # tiny budget → fast response
        agent_name="smoke-test",
        trace_id="langfuse-smoke-test-001",
    )

    print(f"✅  status  : {result.status}")
    print(f"✅  message : {result.message}")
    print()
    print("🔭  Check http://localhost:3002 → Traces for 'smoke-test_llm_call'")

if __name__ == "__main__":
    asyncio.run(main())
