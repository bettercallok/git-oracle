from fastapi import FastAPI, HTTPException
from pydantic import BaseModel
from typing import List
import git
import os
import logging
import sys

# Ensure ai_core modules can be imported
sys.path.append(os.path.dirname(os.path.dirname(os.path.dirname(__file__))))
from shared.structured_output import llm_structured

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

app = FastAPI(title="GitOracle Investigator Agent", version="1.0")

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
    """Fallback Heuristic: Fetch recent git history for the affected files."""
    try:
        repo = git.Repo(repo_path)
        log_output = []
        for file in files:
            # git log -p -n 10 <file>
            log_output.append(f"--- History for {file} ---")
            log = repo.git.log('-p', '-n', str(max_commits), file)
            log_output.append(log)
        return "\n".join(log_output)
    except Exception as e:
        logger.error(f"Git processing failed for {repo_path}: {e}")
        return f"WARNING [LOW_DATA]: Could not fetch git history. Error: {e}"

@app.post("/investigate", response_model=InvestigationResult)
async def investigate(request: InvestigationRequest):
    logger.info(f"Starting investigation for job {request.job_id} in {request.repo_path}")
    
    # 1. Gather Data (The Heuristic Fallback)
    git_history = get_recent_commits(request.repo_path, request.affected_files)
    
    # 2. Formulate Prompt
    prompt_text = f"""
You are the GitOracle Investigator Agent. A bug has been reported.
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
            max_tokens=2048,
            temperature=0.2,
            job_id=request.job_id,
            agent_name="investigator_agent"
        )
        return result
    except Exception as e:
        logger.error(f"Investigation failed: {e}")
        raise HTTPException(status_code=500, detail=str(e))

if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=9001)
