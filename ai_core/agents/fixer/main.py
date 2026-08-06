from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
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
from shared.prompt_registry import fetch_prompt

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

app = FastAPI(title="GitOracle Fixer Agent", version="1.0")
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

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
    human_instructions: Optional[str] = None  # Set when triggered via dashboard or GitHub comment
    target_repo: Optional[str] = None          # GitHub repo to open PR against (owner/repo)
    repo_url: Optional[str] = None             # Git remote to clone for self-testing (GitHub or file://)

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


@app.post("/fix", response_model=PatchResult)
async def execute_fix(request: FixerRequest):
    logger.info(f"Starting ReAct loop for job {request.job_id} in {request.repo_path}")
    
    memory = AgentMemory()
    seen_patches = set()
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
            memories = await memory.recall(request.tenant_id, request.repo_path, request.bug_description, "episodic", top_k=2)
            past_fixes = "\n".join([f"- {m['content']} (Score: {m['metadata'].get('quality_score', 1.0)})" for m in memories])
        except Exception as e:
            logger.warning(f"Could not retrieve episodic memory: {e}")
            past_fixes = "None"
            
        # 3. Formulate Prompt — inject human instructions as top-priority constraint if present
        human_block = ""
        if request.human_instructions:
            human_block = f"""
⚠️ CRITICAL INSTRUCTION FROM USER (HIGHEST PRIORITY — MUST BE FOLLOWED EXACTLY):
{request.human_instructions}
Your patch MUST implement this instruction. All other guidelines are secondary.
"""
        base_prompt = await fetch_prompt(
            "fixer",
            "You are the GitOracle Fixer Agent. A Planner Agent has given you a strict blueprint to fix a bug. "
            "You MUST write a patch (unified diff) that fixes the bug according to the plan."
        )
        prompt_text = f"""
{base_prompt}

Bug Description: {request.bug_description}
{human_block}
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
        
        import httpx
        
        async def run_real_test(job_id: str, repo_path: str, repo_url: str, patch_diff: str) -> bool:
            try:
                async with httpx.AsyncClient() as client:
                    response = await client.post(
                        "http://localhost:8084/test",
                        json={
                            "jobId": job_id,
                            "repoUrl": repo_url,
                            "repoPath": repo_path,
                            "patchDiff": patch_diff,
                            "framework": "PYTEST"
                        },
                        timeout=130.0
                    )
                    response.raise_for_status()
                    data = response.json()
                    logger.info(f"Real Test Runner result: {data.get('allPassed', data.get('success'))}")
                    passed = data.get('allPassed', data.get('passed', data.get('success', False)))
                    if not passed:
                        logger.info(f"Test Logs: {data.get('logs', '')}")
                    return passed
            except Exception as e:
                logger.error(f"Error calling real test runner: {e}")
                return False

        # 6. Run tests (Layer 6 real execution)
        if request.job_id.startswith("mock-test-"):
            logger.info("Bypassing tests for mock-test- job.")
            tests_passed = True
        else:
            tests_passed = await run_real_test(
                request.job_id, request.repo_path, request.repo_url or "", patch.diff
            )
        
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

import asyncio
from shared.kafka_consumer import KafkaEventConsumer
from shared.kafka_producer import KafkaEventProducer

async def handle_fix_job(payload: dict):
    logger.info(f"Received Kafka event for fixer: {payload}")
    
    job_id = payload.get("job_id", "unknown")
    repo_path = payload.get("repo_path", "")
    repo_url = payload.get("repo_url", "")
    plan_dict = payload.get("plan", {})
    human_instructions = payload.get("human_instructions")
    target_repo = payload.get("target_repo", "")

    plan = PlannerOutput(**plan_dict)

    bug_desc = payload.get("bug_description", "Fixing error based on plan")
    if human_instructions:
        bug_desc = human_instructions  # user's instructions become the primary description

    request = FixerRequest(
        tenant_id="00000000-0000-0000-0000-000000000000",
        repo_path=repo_path,
        bug_description=bug_desc,
        plan=plan,
        job_id=job_id,
        human_instructions=human_instructions,
        target_repo=target_repo if target_repo else None,
        repo_url=repo_url if repo_url else None
    )
    
    try:
        result = await execute_fix(request)
        producer = KafkaEventProducer()
        if result.success and result.patch:
            fix_payload = {
                "jobId": job_id,
                "patch": result.patch.diff,
                "targetRepo": target_repo or "",
                "humanInstructions": human_instructions or "",
                "isRegeneration": bool(human_instructions)
            }
            await producer.publish("fix-generated", fix_payload)
            logger.info(f"Published fix-generated for job {job_id} (human_directed={bool(human_instructions)})")
        else:
            logger.warning(f"Fixer failed to produce a valid patch for job {job_id}")
            await producer.publish("job-escalated", {
                "jobId": job_id,
                "reason": result.escalation_report or "Fixer Agent exhausted all attempts and could not fix the bug.",
                "confidenceScore": 0.0
            })
            logger.info(f"Published job-escalated for job {job_id}")
    except Exception as e:
        logger.error(f"Fix execution failed for job {job_id}: {e}")

@app.on_event("startup")
async def startup_event():
    consumer = KafkaEventConsumer(topic="job.events.fix", group_id="fixer-agent-group")
    asyncio.create_task(consumer.consume(handle_fix_job))

if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=9002)
