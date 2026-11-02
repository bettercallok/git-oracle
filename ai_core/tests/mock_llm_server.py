"""A deterministic stand-in for the LLM, for the pipeline end-to-end test.

The old mock returned {"patch": ...} / {"strategy": ..., "files_to_modify": ...}
— shapes no agent asks for — and picked between them by guessing from prompt
wording ("diff" in the investigator's prompt got it the fixer's reply), so it
could never drive the real pipeline. No test used it.

This one answers each agent with a valid object for the schema it asked for.
llm_structured() (shared/structured_output.py) appends the JSON schema to the
system message, and every schema carries its model's name as "title", so the
schema itself says which agent is calling — no prompt guessing.

The answers fix eval case1 (case/npe in the golden repo, see
eval/setup_test_repo.py): the planner points at UserService.java and the fixer's
search text is a line that exists verbatim on that branch, so build_unified_diff
accepts it and the real Maven tests pass with the patch applied.

Run:  python ai_core/tests/mock_llm_server.py        (port 18089, or MOCK_LLM_PORT)
Point agents at it with LLM_BASE_URL=http://127.0.0.1:18089/v1/chat/completions.
Port 18089, not the old 8089, which Kafka UI uses.
"""
import json
import os
import re

import uvicorn
from fastapi import FastAPI, Request

app = FastAPI(title="Mock LLM Server")

USER_SERVICE = "src/main/java/com/example/UserService.java"
SEARCH = "        String userId = user.getId();\n"
REPLACE = (
    "        if (user == null) {\n"
    "            throw new IllegalArgumentException(\"User cannot be null\");\n"
    "        }\n"
    "        String userId = user.getId();\n"
)


def _investigation(prompt: str) -> dict:
    # Blame the commit whose subject mentions processPayment if the prompt's git
    # log shows it; any 40-hex SHA otherwise. The e2e test doesn't assert on the
    # cause, but a real-looking answer keeps rootCommit realistic.
    sha = None
    for line in prompt.splitlines():
        found = re.search(r"\b[0-9a-f]{40}\b", line)
        if found and "processPayment" in line:
            sha = found.group(0)
            break
    if sha is None:
        found = re.search(r"\b[0-9a-f]{40}\b", prompt)
        sha = found.group(0) if found else "0" * 40
    return {
        "ranked_causes": [{
            "commit_sha": sha, "author": "Test User", "message": "refactor: simplify processPayment",
            "causal_effect_score": 0.9, "reasoning": "Removed the null guard in processPayment.",
        }],
        "narrative": "processPayment dereferences a null user since its guard was removed.",
        "confidence_score": 0.9,
        "affected_files": [USER_SERVICE],
        "recommended_strategy": "surgical_patch",
    }


ANSWERS = {
    "InvestigationResult": _investigation,
    "PlannerOutput": lambda _p: {
        "strategy": "surgical_patch", "affected_files": [USER_SERVICE],
        "affected_functions": ["processPayment"], "max_lines_to_change": 10,
        "reasoning": "Restore the null check before user.getId().", "confidence": 0.9,
    },
    "SearchReplaceEdit": lambda _p: {
        "file_path": USER_SERVICE, "search": SEARCH, "replace": REPLACE,
        "summary": "Reject a null user explicitly instead of throwing a raw NPE.", "confidence": 0.9,
    },
    "ReviewReply": lambda _p: {
        "reply": "Good point, I'll address it.", "stance": "acknowledge", "action_needed": True, "concern_severity": 0.5,
    },
    "AnalyzeResponse": lambda _p: {"answer": "Mock analysis.", "suggested_action": "NONE", "confidence": 1.0},
}


def _schema_title(system_prompt: str) -> str | None:
    for title in ANSWERS:
        if f'"title": "{title}"' in system_prompt:
            return title
    return None


@app.post("/v1/chat/completions")
async def chat_completions(request: Request):
    body = await request.json()
    messages = body.get("messages", [])
    system_prompt = messages[0]["content"] if messages and messages[0].get("role") == "system" else ""
    prompt = "\n".join(str(m.get("content", "")) for m in messages)
    title = _schema_title(system_prompt)
    content = json.dumps(ANSWERS[title](prompt)) if title else "{}"
    return {
        "id": "mock", "object": "chat.completion", "model": body.get("model", "mock"),
        "choices": [{"index": 0, "finish_reason": "stop",
                     "message": {"role": "assistant", "content": content}}],
        # Without usage, token reporting was skipped entirely.
        "usage": {"prompt_tokens": 100, "completion_tokens": 50, "total_tokens": 150},
    }


# shared/memory.py derives an embeddings URL from LLM_BASE_URL; answer it so
# memory calls succeed instead of logging connection errors on every attempt.
@app.post("/api/embeddings")
@app.post("/v1/embeddings")
async def embeddings(request: Request):
    return {"embedding": [0.0] * 384, "data": [{"embedding": [0.0] * 384, "index": 0}]}


if __name__ == "__main__":
    uvicorn.run(app, host="127.0.0.1", port=int(os.environ.get("MOCK_LLM_PORT", "18089")))
