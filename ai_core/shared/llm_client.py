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
        
        # Adding OPENAI_API_KEY support for cloud providers
        headers = {}
        api_key = os.environ.get("OPENAI_API_KEY")
        if api_key:
            headers["Authorization"] = f"Bearer {api_key}"
            
        # Optional: Groq requires 'model' field even for standard completions endpoint sometimes,
        # but let's try injecting model name from env if needed.
        model_name = os.environ.get("LLM_MODEL_NAME")
        if model_name:
            payload["model"] = model_name
        
        try:
            async with httpx.AsyncClient() as client:
                response = await client.post(
                    f"{self.base_url}",
                    json=payload,
                    headers=headers,
                    timeout=300.0 # Generation can take a while
                )
                response.raise_for_status()
                data = response.json()
                return data["choices"][0]["message"]["content"]
        except Exception as e:
            logger.error(f"Failed to connect to LLM server at {self.base_url}: {e}")
            raise
