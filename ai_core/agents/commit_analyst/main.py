"""
GitOracle Commit Analyst Agent — port 9004

Analyses a single git commit diff and answers developer questions about it in
conversational multi-turn style.  Chat history is ephemeral (held in the caller's
React state) so this service is completely stateless.

POST /analyze
  Body: {
    "sha":           "abc1234...",
    "repo":          "owner/repo",
    "commitMessage": "feat: ...",
    "diff":          "--- a/foo.py\n+++ b/foo.py\n...",
    "question":      "Could this introduce a memory leak?",
    "chatHistory":   [{"role": "user", "content": "..."}, ...]
  }
  Response: {
    "answer":           "Yes, the leak occurs at line 42 because...",
    "suggested_action": "FIX" | "INVESTIGATE" | "NONE",
    "tokens_used":      412
  }

GET /health
  Returns {"status": "ok", "agent": "commit_analyst"}
"""

from fastapi import Depends, FastAPI, HTTPException
from pydantic import BaseModel
from typing import List
import os
import uuid
import logging
import sys

sys.path.append(os.path.dirname(os.path.dirname(os.path.dirname(__file__))))
from shared.structured_output import llm_structured
from shared.internal_auth import require_internal_token

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

app = FastAPI(title="GitOracle Commit Analyst", version="1.0", dependencies=[Depends(require_internal_token)])
# No CORSMiddleware.
#
# This agent used to run allow_origins=["*"] together with
# allow_credentials=True. That pairing is rejected by every browser (the spec
# forbids a wildcard origin on a credentialed request), so it never did what it
# looked like it did — but it advertised an intent to be called cross-origin by
# a browser, which is exactly what this service must not be.
#
# Nothing browser-side calls the agents. The dashboard talks only to the API
# gateway on :8080, which owns CORS centrally (and has a test pinning that it is
# the only place setting the header — a duplicate broke CORS outright once).
# Since C5 these agents bind 127.0.0.1 and require X-Internal-Token, so their
# callers are other services, and service-to-service calls have no origin and
# need no CORS at all.
#
# Removing it is not cosmetic: CORS headers on a service that should never be
# reached by a browser turn a future SSRF or a misrouted proxy into something a
# page can read the response of, instead of something the browser blocks.

# ─── Request / Response Models ─────────────────────────────────────────────────

class ChatMessage(BaseModel):
    role: str    # "user" or "assistant"
    content: str

class AnalyzeRequest(BaseModel):
    sha: str
    repo: str
    commitMessage: str
    diff: str
    question: str
    chatHistory: List[ChatMessage] = []

class AnalyzeResponse(BaseModel):
    answer: str
    suggested_action: str     # "FIX" | "INVESTIGATE" | "NONE"
    confidence: float = 1.0

# ─── Helpers ───────────────────────────────────────────────────────────────────

# Maximum diff characters to send to the LLM. Since we use an API backend now
# (not llama.cpp with a hard 8192 token window), we can be more generous.
MAX_DIFF_CHARS = 12_000

SYSTEM_PROMPT = """You are the GitOracle Commit Analyst — an expert AI code reviewer
specialising in git commit archaeology. You help developers understand, critique, and
reason about specific code changes captured in a git diff.

Your responsibilities:
1. Answer the developer's question about the commit precisely and concisely.
2. Identify potential bugs, regressions, performance issues, or security concerns
   introduced by the diff when relevant to the question.
3. Suggest what action should be taken:
   - "FIX"         — you identified a concrete problem that GitOracle can autonomously fix.
   - "INVESTIGATE" — you found something suspicious that needs human investigation first.
   - "NONE"        — no action needed; the commit looks fine or the question is purely informational.

Guidelines:
- Be direct and technical. The audience is an experienced developer.
- Reference specific file names and line numbers from the diff when possible.
- Keep your answer under 400 words unless the question demands deep analysis.
- Do NOT make up code. Only reference what is present in the diff."""


def _build_messages(request: AnalyzeRequest) -> list[dict]:
    """Build the full message list from system prompt + diff context + chat history + question."""

    diff_snippet = request.diff
    if len(diff_snippet) > MAX_DIFF_CHARS:
        diff_snippet = diff_snippet[:MAX_DIFF_CHARS] + "\n\n... [diff truncated]"

    context_block = f"""COMMIT: {request.sha[:7]} in {request.repo}
COMMIT MESSAGE: {request.commitMessage}

DIFF:
{diff_snippet}
"""

    messages = [{"role": "system", "content": SYSTEM_PROMPT}]

    # Inject the commit context as the first user turn if this is a fresh conversation
    if not request.chatHistory:
        messages.append({
            "role": "user",
            "content": f"{context_block}\n\nQuestion: {request.question}"
        })
    else:
        # Multi-turn: prepend context to the oldest user message in history
        # so the model always has the diff in scope without repeating it every turn.
        messages.append({
            "role": "user",
            "content": f"{context_block}\n\nFirst question: {request.chatHistory[0].content}"
        })
        for i, msg in enumerate(request.chatHistory[1:], 1):
            messages.append({"role": msg.role, "content": msg.content})
        # Now append the new question
        messages.append({"role": "user", "content": request.question})

    return messages

# ─── Endpoints ─────────────────────────────────────────────────────────────────

@app.get("/health")
async def health():
    return {"status": "ok", "agent": "commit_analyst"}


@app.post("/analyze", response_model=AnalyzeResponse)
async def analyze(request: AnalyzeRequest):
    logger.info(
        "Analyzing commit %s in %s | turns=%d | question='%s'",
        request.sha[:7], request.repo, len(request.chatHistory), request.question[:80]
    )

    messages = _build_messages(request)

    try:
        result = await llm_structured(
            messages=messages,
            output_schema=AnalyzeResponse,
            max_tokens=1024,
            temperature=0.3,
            agent_name="commit_analyst",
            job_id=None,   # Not a tracked job — ephemeral chat
        )
        logger.info(
            "Commit Analyst answered for %s/%s: action=%s",
            request.repo, request.sha[:7], result.suggested_action
        )
        return result
    except Exception:
        # Logged, not returned. An httpx error from the LLM call carries the
        # provider URL, and provider errors frequently echo back the request —
        # including the API key in an Authorization header on some clients.
        correlation_id = str(uuid.uuid4())
        logger.exception("LLM call failed for commit_analyst [correlationId=%s]", correlation_id)
        raise HTTPException(
            status_code=502,
            detail=f"LLM inference failed. correlationId={correlation_id}",
        )


# ─── Entry Point ───────────────────────────────────────────────────────────────

if __name__ == "__main__":
    import uvicorn
    port = int(os.environ.get("PORT", 9004))
    logger.info("Starting Commit Analyst agent on port %d", port)
    uvicorn.run(app, host=os.getenv("BIND_HOST", "127.0.0.1"), port=port)
