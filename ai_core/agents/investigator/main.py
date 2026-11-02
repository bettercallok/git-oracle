from fastapi import Depends, FastAPI, HTTPException
from pydantic import BaseModel
from typing import List
import git
import os
import logging
import sys

# Ensure ai_core modules can be imported
sys.path.append(os.path.dirname(os.path.dirname(os.path.dirname(__file__))))
from shared.structured_output import llm_structured
from shared.prompt_registry import fetch_prompt_versioned, report_prompt_version
from shared.internal_auth import require_internal_token
from shared.job_events import report_event
from shared.body_limit import BodySizeLimitMiddleware

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

app = FastAPI(title="GitOracle Investigator Agent", version="1.0", dependencies=[Depends(require_internal_token)])
app.add_middleware(BodySizeLimitMiddleware)
# No CORS middleware: agents are called server-to-server only (orchestrator,
# other agents), never from a browser. allow_origins=["*"] with
# allow_credentials=True let any web page a developer visited call them.

class InvestigationRequest(BaseModel):
    tenant_id: str
    repo_path: str
    bug_description: str
    affected_files: List[str]
    job_id: str = "debug-job"

class CausalCandidate(BaseModel):
    commit_sha: str
    author: str
    message: str
    causal_effect_score: float
    reasoning: str

class InvestigationResult(BaseModel):
    ranked_causes: List[CausalCandidate]
    narrative: str
    confidence_score: float
    affected_files: List[str]
    recommended_strategy: str

def get_recent_commits(repo_path: str, files: List[str], max_commits: int = 10) -> str:
    """Fallback Heuristic: Fetch recent git history for the affected files.

    affected_files is currently always empty at the call site (file-level
    inference isn't implemented yet), which made this silently return "" —
    the investigator had zero real git history and fully fabricated
    ranked_causes/commit SHAs on every run (confirmed live). Fall back to
    the whole repo's recent history so there's at least real signal to
    reason from until per-file inference exists.
    """
    try:
        repo = git.Repo(repo_path)
        log_output = []
        if not files:
            log_output.append("--- Recent repo-wide history (no specific affected files known yet) ---")
            log_output.append(repo.git.log('-p', '-n', str(max_commits)))
            return "\n".join(log_output)
        for file in files:
            # git log -p -n 10 <file>
            log_output.append(f"--- History for {file} ---")
            log = repo.git.log('-p', '-n', str(max_commits), file)
            log_output.append(log)
        return "\n".join(log_output)
    except Exception as e:  # noqa: BLE001 — missing git history is passed to the LLM as LOW_DATA instead of failing
        logger.error(f"Git processing failed for {repo_path}: {e}")
        return f"WARNING [LOW_DATA]: Could not fetch git history. Error: {e}"

@app.post("/investigate", response_model=InvestigationResult)
async def investigate(request: InvestigationRequest):
    logger.info(f"Starting investigation for job {request.job_id} in {request.repo_path}")
    
    # 1. Gather Data (The Heuristic Fallback)
    git_history = get_recent_commits(request.repo_path, request.affected_files)
    
    # 2. Fetch base prompt from the dynamic prompt registry, then formulate the full prompt
    base_prompt, prompt_version = await fetch_prompt_versioned(
        "investigator",
        "You are the GitOracle Investigator Agent. A bug has been reported."
    )
    await report_prompt_version(request.job_id, "investigator", prompt_version)
    prompt_text = f"""
{base_prompt}
Bug Description: {request.bug_description}
Affected Files: {', '.join(request.affected_files)}

Recent Git History for these files:
{git_history[:8000]} # Trim to fit context window

Analyze the git history and determine which commits most likely introduced the bug.
Rank them and provide a causal_effect_score (0.0 to 1.0) and reasoning for each.
"""
    messages = [
        {"role": "system", "content": "You are an expert debugging AI. Analyze git diffs to find root causes."},
        {"role": "user", "content": prompt_text}
    ]

    # 3. Call LLM (Layer 3b)
    try:
        result = await llm_structured(
            messages=messages,
            output_schema=InvestigationResult,
            # 4096, not 2048: InvestigationResult requires a reasoning string per
            # ranked cause, so output length scales with how many commits the repo
            # history offers. On a real repo the model ran out of completion budget
            # mid-JSON and Groq rejected the whole request with 400
            # json_validate_failed ("max completion tokens reached before generating
            # a valid document") — a hard failure, not a truncated result. The
            # 3-commit eval repo never hit it, which is why it went unnoticed.
            max_tokens=4096,
            temperature=0.2,
            job_id=request.job_id,
            agent_name="investigator_agent"
        )
        return result
    except Exception as e:  # noqa: BLE001 — HTTP boundary: logged and returned as a 500
        logger.error(f"Investigation failed: {e}")
        raise HTTPException(status_code=500, detail=str(e))

# ==========================================
# Phase 3: Real Kafka Event Integration
# ==========================================
from shared.kafka_consumer import KafkaEventConsumer
from shared import topics
from shared.kafka_producer import get_producer
from shared.consumer_lifespan import run_consumer_with_app
from shared.health import add_health_route

async def handle_investigate_job(payload: dict):
    logger.info(f"Received Kafka event for investigation: {payload}")
    
    job_id = payload.get("job_id", "unknown-job")
    repo_path = payload.get("repo_path", "")
    error_id = payload.get("error_id", "unknown")
    raw_payload = payload.get("raw_payload")
    human_instructions = payload.get("human_instructions") or ""
    target_repo = payload.get("target_repo") or ""
    branch = payload.get("branch") or ""

    # raw_payload carries the real stack trace / webhook body captured by
    # error-ingestor. Falling back to the generic error_id-only description
    # when it's absent (e.g. not every ingestion path populates it) rather
    # than requiring it, but using the real signal whenever it's there —
    # without it the investigator has nothing but an opaque error_id string
    # to reason from and reliably hallucinates a plausible-sounding but
    # wrong root cause (confirmed live, repeatedly).
    #
    # human_instructions is a different kind of signal: observed evidence vs.
    # stated intent. Both are useful to an investigation, so use whichever are
    # present rather than picking one — deduped, because a dashboard-triggered
    # job sets both fields to the same text.
    _parts = []
    if raw_payload:
        _parts.append(str(raw_payload))
    if human_instructions and human_instructions != raw_payload:
        _parts.append(f"User instruction: {human_instructions}")
    bug_description = "\n\n".join(_parts) if _parts else f"Error {error_id} reported from ingestor."

    request = InvestigationRequest(
        tenant_id="00000000-0000-0000-0000-000000000000",
        repo_path=repo_path,
        bug_description=bug_description,
        affected_files=[], # To be implemented via git diff inference
        job_id=job_id
    )
    
    try:
        # 1. Execute AI Investigation
        await report_event(job_id, "investigator", "Ranking recent commits as candidate causes")
        investigation_result = await investigate(request)
        logger.info(f"Investigation Complete for job {job_id}")
        await report_event(
            job_id, "investigator",
            f"Ranked {len(investigation_result.ranked_causes)} candidate cause(s), "
            f"confidence {investigation_result.confidence_score:.2f}",
            details={"affected_files": investigation_result.affected_files[:20]},
        )
        
        # 2. Publish to Planner Agent
        producer = get_producer()
        plan_payload = {
            "job_id": job_id,
            "repo_url": payload.get("repo_url", ""),
            "repo_path": repo_path,
            "error_id": error_id,
            "investigation_result": investigation_result.dict(),
            # Pass-through so the Fixer, two hops downstream, still receives them.
            "human_instructions": human_instructions,
            "target_repo": target_repo,
            "branch": branch,
        }
        await producer.publish(topics.JOB_PLAN, plan_payload)
        logger.info(f"Published job.events.plan for job {job_id}")
    except Exception as e:  # noqa: BLE001 — any investigation failure escalates the job (below)
        # Escalate rather than just logging. The orchestrator moves a job to
        # INVESTIGATING before dispatching here and nothing else ever moves it on, so
        # an investigation that throws leaves the job stranded in a non-terminal state
        # indefinitely — invisible in the Escalation Queue and never retried.
        # Confirmed live: three jobs sat in INVESTIGATING after the Groq daily token
        # quota was exhausted, with no indication anywhere in the UI that they were dead.
        logger.error(f"Investigation event failed for job {job_id}: {e}")
        try:
            await get_producer().publish(topics.JOB_ESCALATED, {
                "jobId": job_id,
                "reason": f"Investigator Agent failed: {e}",
                # No confidenceScore: a crash has none, and a placeholder 0.0
                # showed in the Escalation Queue as a real, very low score.
            })
            logger.info(f"Published job-escalated for job {job_id}")
        except Exception as publish_error:  # noqa: BLE001 — escalation publish failed; JobWatchdog still escalates the stalled job
            logger.error(f"Could not escalate job {job_id}: {publish_error}")

# Created at import so /health can report on it; runs for the app's lifetime.
_consumer = KafkaEventConsumer(topic=topics.JOB_INVESTIGATE, group_id="investigator-agent-group")
add_health_route(app, "investigator", _consumer)
run_consumer_with_app(app, _consumer, handle_investigate_job)

if __name__ == "__main__":
    from shared.config import check_startup_config
    check_startup_config("investigator", needs_llm=True)
    import uvicorn
    # Loopback by default — this agent used to bind 0.0.0.0 (network-reachable)
    # regardless of the internal-token dependency wired in below. Override only
    # for a real container network where callers reach it by service name.
    uvicorn.run(app, host=os.getenv("BIND_HOST", "127.0.0.1"), port=9001)
