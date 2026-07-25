import os
import httpx
import json
import logging

logger = logging.getLogger(__name__)

class LLMClient:
    def __init__(self):
        # Default to localhost if LLM_SERVER_URL is not set (i.e. not in tests)
        self.base_url = os.getenv("LLM_SERVER_URL", "http://localhost:8080/v1")
        
    async def chat_completion(self, system_prompt: str, user_prompt: str, temperature: float = 0.1) -> str:
        """Hits the Llama.cpp standard OpenAI-compatible completions endpoint."""
        payload = {
            "messages": [
                {"role": "system", "content": system_prompt},
                {"role": "user", "content": user_prompt}
            ],
            "temperature": temperature,
            "max_tokens": 4096
        }
        
        try:
            async with httpx.AsyncClient() as client:
                response = await client.post(
                    f"{self.base_url}/chat/completions",
                    json=payload,
                    timeout=300.0 # Generation can take a while
                )
                response.raise_for_status()
                data = response.json()
                return data["choices"][0]["message"]["content"]
        except Exception as e:
            logger.error(f"Failed to connect to LLM server at {self.base_url}: {e}")
            raise
