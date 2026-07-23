from fastapi import FastAPI, HTTPException
from pydantic import BaseModel
from typing import List, Optional
import os
import logging
import sys
import hashlib

# Ensure ai_core modules can be imported
sys.path.append(os.path.dirname(os.path.dirname(os.path.dirname(__file__))))
from shared.structured_output import llm_structured
from shared.memory import AgentMemory

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

app = FastAPI(title="GitOracle Fixer Agent", version="1.0")

class FixStrategy(str):
    pass

class PlannerOutput(BaseModel):
    strategy: str
    affected_files: List[str]
    affected_functions: List[str]
    max_lines_to_change: int
    reasoning: str
    confidence: float

class FixerRequest(BaseModel):
    tenant_id: str
    repo_path: str
    bug_description: str
    plan: PlannerOutput
    job_id: str = "debug-job"

class PatchOutput(BaseModel):
    diff: str                   # unified diff format
    summary: str                # one-line description of change
    files_modified: List[str]   # must match Planner's affected_files
    new_tests: Optional[str] = None # optional: new test cases agent wrote
    confidence: float

class PatchResult(BaseModel):
    patch: Optional[PatchOutput]
    attempts: int
    success: bool
    escalation_report: Optional[str] = None

async def mock_test_runner(patch: PatchOutput, repo_path: str) -> bool:
    """
    TODO (Layer 6): Replace this mock with the actual Test Runner 
    that spins up a secure Docker container and runs pytest/maven.
    For now, we mock success if the patch has high confidence.
    """
    logger.info("Mock Test Runner executing...")
    return patch.confidence > 0.5

@app.post("/fix", response_model=PatchResult)
async def execute_fix(request: FixerRequest):
    logger.info(f"Starting ReAct loop for job {request.job_id} in {request.repo_path}")
    
    memory = AgentMemory()
    seen_patches = set()
    history = []
    hint = ""
    
    # 1. Fetch source code for context
    source_context = ""
    for file_path in request.plan.affected_files:
        full_path = os.path.join(request.repo_path, file_path)
        try:
            with open(full_path, "r") as f:
                source_context += f"\n--- {file_path} ---\n{f.read()}\n"
        except Exception as e:
            logger.warning(f"Could not read {full_path}: {e}")
            source_context += f"\n--- {file_path} ---\n[File not found or unreadable]\n"

    # ReAct Loop (Up to 3 attempts to save time during testing, normally 5)
    for attempt in range(3):
        logger.info(f"Fixer Attempt {attempt + 1}")
        
        # 2. Retrieve Episodic Memory
        try:
            memories = await memory.recall(request.tenant_id, request.repo_path, "episodic", request.bug_description, top_k=2)
            past_fixes = "\n".join([f"- {m['memory']} (Score: {m['metadata'].get('quality_score', 1.0)})" for m in memories])
        except Exception as e:
            logger.warning(f"Could not retrieve episodic memory: {e}")
            past_fixes = "None"
            
        # 3. Formulate Prompt
        prompt_text = f"""
You are the GitOracle Fixer Agent. A Planner Agent has given you a strict blueprint to fix a bug.
You MUST write a patch (unified diff) that fixes the bug according to the plan.

Bug Description: {request.bug_description}

Architect's Blueprint:
Strategy: {request.plan.strategy}
Max Lines to Change: {request.plan.max_lines_to_change}
Reasoning: {request.plan.reasoning}

Past Successful Fixes for Similar Bugs:
{past_fixes}

Source Code Context:
{source_context[:4000]}  # Trimmed for safety

{hint}

Generate a PatchOutput containing the diff and a short summary.
"""
        messages = [
            {"role": "system", "content": "You are a master programmer AI. You write precise unified diffs to fix bugs."},
            {"role": "user", "content": prompt_text}
        ]

        # 4. Call LLM (Layer 3b)
        try:
            patch = await llm_structured(
                messages=messages,
                output_schema=PatchOutput,
                max_tokens=1024,
                temperature=0.2 + (attempt * 0.2), # Increase temp on retries for creativity
                job_id=request.job_id,
                agent_name="fixer_agent"
            )
        except Exception as e:
            logger.error(f"LLM call failed: {e}")
            continue

        # 5. Self-Healing Loop Detection
        patch_hash = hashlib.sha256(patch.diff.encode('utf-8')).hexdigest()
        if patch_hash in seen_patches:
            logger.warning("Loop detected! Agent generated the exact same patch.")
            hint = "WARNING: Your previous patch failed the tests. You MUST try a fundamentally different approach. Do not output the same diff."
            continue
        
        seen_patches.add(patch_hash)
        
        # 6. Run tests (Mocked for now - TODO Layer 6)
        tests_passed = await mock_test_runner(patch, request.repo_path)
        
        if tests_passed:
            # 7. Store successful fix in episodic memory
            try:
                episode_text = f"Fixed bug '{request.bug_description}' using strategy {request.plan.strategy}. Key change: {patch.summary}."
                await memory.remember(request.tenant_id, request.repo_path, "episodic", episode_text, metadata={"quality_score": 1.0})
                logger.info("Saved success to Episodic Memory.")
            except Exception as e:
                logger.warning(f"Failed to store memory: {e}")
                
            return PatchResult(patch=patch, attempts=attempt + 1, success=True)
        else:
            hint = f"WARNING: Your patch '{patch.summary}' failed the tests. Please try again and fix any syntax or logic errors."
            
    # Escalation
    return PatchResult(patch=None, attempts=3, success=False, escalation_report="Fixer Agent exhausted all attempts and could not fix the bug.")

if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=9002)
