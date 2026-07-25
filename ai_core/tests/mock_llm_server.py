import json
from fastapi import FastAPI, Request
import uvicorn

app = FastAPI(title="Mock LLM Server")

# Deterministic responses for testing
MOCK_RESPONSES = {
    "fixer_valid": {
        "choices": [{
            "message": {
                "content": json.dumps({"patch": "--- a/UserService.java\n+++ b/UserService.java\n@@ -40,1 +40,1 @@\n- String name = null;\n+ String name = \"Unknown\";", "confidence": 0.95})
            }
        }]
    },
    "planner_valid": {
         "choices": [{
            "message": {
                "content": json.dumps({"strategy": "Replace null with default value", "files_to_modify": ["UserService.java"]})
            }
        }]
    }
}

@app.post("/v1/chat/completions")
async def chat_completions(request: Request):
    body = await request.json()
    messages = body.get("messages", [])
    system_prompt = messages[0]["content"] if messages else ""
    
    # Return different mock responses based on prompt heuristics
    if "Fixer" in system_prompt or "diff" in system_prompt:
        return MOCK_RESPONSES["fixer_valid"]
    else:
        return MOCK_RESPONSES["planner_valid"]

if __name__ == "__main__":
    uvicorn.run(app, host="127.0.0.1", port=8089)
