from fastapi import Depends, FastAPI, HTTPException
from pydantic import BaseModel
from enum import Enum
import os
import uuid
import logging
import sys

# Ensure ai_core modules can be imported
sys.path.append(os.path.dirname(os.path.dirname(os.path.dirname(__file__))))
from shared.structured_output import llm_structured
from shared.internal_auth import require_internal_token

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

app = FastAPI(title="GitOracle Reviewer Agent", version="1.0", dependencies=[Depends(require_internal_token)])
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

class ReviewStance(str, Enum):
    DEFEND            = "defend"            # reviewer concern is wrong
    ACKNOWLEDGE_ISSUE = "acknowledge"       # reviewer found a real bug
    PROPOSE_ALT       = "propose_alt"       # offer a better approach

class ReviewReply(BaseModel):
    reply: str
    stance: ReviewStance
    action_needed: bool          
    concern_severity: float      # 0.0=minor style to 1.0=correctness bug

class ReviewRequest(BaseModel):
    tenant_id: str
    repo_path: str
    bug_description: str
    fixer_patch: str
    human_comment: str
    job_id: str = "debug-job"

@app.post("/review", response_model=ReviewReply)
async def process_review(request: ReviewRequest):
    logger.info(f"Processing code review for job {request.job_id} in {request.repo_path}")
    
    # Formulate Prompt
    prompt_text = f"""
You are the GitOracle Reviewer Agent. Another AI (the Fixer Agent) has written a patch to fix a bug.
A Human Developer has just left a Code Review comment on the patch.

Original Bug Description: {request.bug_description}

Fixer Agent's Patch:
{request.fixer_patch}

Human Reviewer's Comment:
"{request.human_comment}"

Analyze the human's comment. 
If the human is WRONG (e.g. they missed something), choose stance 'defend'.
If the human found a REAL BUG in the patch (e.g. SQL injection, logic error), choose stance 'acknowledge'.
If the human is asking for a different approach, choose stance 'propose_alt'.

Evaluate the severity of their concern from 0.0 to 1.0.
If stance is 'acknowledge' and severity > 0.7, set action_needed = True.
"""
    messages = [
        {"role": "system", "content": "You are a self-aware, senior AI developer. You defend your code if you are right, but acknowledge mistakes if you are wrong."},
        {"role": "user", "content": prompt_text}
    ]

    # Call LLM (Layer 3b)
    try:
        reply = await llm_structured(
            messages=messages,
            output_schema=ReviewReply,
            max_tokens=1024,
            temperature=0.3, 
            job_id=request.job_id,
            agent_name="reviewer_agent"
        )
        
        # Enforce the business logic from the master plan
        if reply.stance == ReviewStance.ACKNOWLEDGE_ISSUE and reply.concern_severity > 0.7:
            reply.action_needed = True
            logger.warning(f"HIGH SEVERITY ISSUE ACKNOWLEDGED. Emitting 'fix-regeneration-needed' event for job {request.job_id}.")
            # TODO (Layer 8): Publish 'fix-regeneration-needed' to Kafka here
            
        return reply
    except Exception:
        # The exception text is logged, never returned. str(e) on an arbitrary
        # exception leaks internals to the caller — asyncpg puts the DSN
        # (including the database password) in its connection errors, httpx
        # names internal hosts and ports, and tracebacks carry filesystem
        # paths. The caller gets a correlation id to quote instead.
        correlation_id = str(uuid.uuid4())
        logger.exception("Review processing failed [correlationId=%s]", correlation_id)
        raise HTTPException(
            status_code=500,
            detail=f"Review processing failed. correlationId={correlation_id}",
        )

if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host=os.getenv("BIND_HOST", "127.0.0.1"), port=9003)
