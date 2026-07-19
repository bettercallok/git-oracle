import asyncio
import logging
import sys
import os

# Add ai_core to sys.path so we can import shared
sys.path.append(os.path.dirname(__file__))

from pydantic import BaseModel
from shared.structured_output import llm_structured, LLMUnavailableError

# Configure basic logging so we can see the tenacity retries
logging.basicConfig(level=logging.DEBUG)

class TestSchema(BaseModel):
    status: str

async def test_fallback():
    # Intentionally point to a dead port to trigger retries and fallback
    dead_url = "http://localhost:9999/v1/chat/completions"
    messages = [{"role": "user", "content": "Hello"}]
    
    print("\n--- Starting Fallback Test ---")
    print(f"Targeting DEAD primary LLM: {dead_url}")
    print("Expect: 3 retries (with exponential backoff) followed by an LLMUnavailableError.\n")
    
    try:
        await llm_structured(
            messages=messages,
            output_schema=TestSchema,
            base_url=dead_url
        )
        print("❌ Test failed: The function succeeded but should have raised LLMUnavailableError!")
    except LLMUnavailableError as e:
        print("\n✅ Test passed: Successfully caught LLMUnavailableError!")
        print(f"Exception Message: {e}")
    except Exception as e:
        print(f"\n❌ Test failed: Caught the wrong exception: {type(e).__name__} - {e}")

if __name__ == "__main__":
    asyncio.run(test_fallback())
