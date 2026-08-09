from fastapi import FastAPI, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel
from typing import List
from enum import Enum
import os
import logging
import sys

# Ensure ai_core modules can be imported
sys.path.append(os.path.dirname(os.path.dirname(os.path.dirname(__file__))))
from shared.structured_output import llm_structured
from shared.memory import SemanticMemory
from shared.prompt_registry import fetch_prompt_versioned, report_prompt_version

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

app = FastAPI(title="GitOracle Planner Agent", version="1.0")
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

class FixStrategy(str, Enum):
    REWRITE_FUNCTION   = "rewrite_function"
    SURGICAL_PATCH     = "surgical_patch"
    DEPENDENCY_UPDATE  = "dependency_update"
    SYNC_FIX           = "synchronization_fix"
    API_REPLACEMENT    = "api_replacement"

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

class PlannerRequest(BaseModel):
    tenant_id: str
    repo_path: str
    bug_description: str
    investigation_result: InvestigationResult
    job_id: str = "debug-job"

class PlannerOutput(BaseModel):
    strategy: FixStrategy
    affected_files: List[str]
    affected_functions: List[str]
    max_lines_to_change: int
    reasoning: str
    confidence: float

@app.post("/plan", response_model=PlannerOutput)
async def create_plan(request: PlannerRequest):
    logger.info(f"Starting planning for job {request.job_id} in {request.repo_path}")
    
    # 1. Retrieve Semantic Memory (Architectural Rules) from Qdrant
    memory = SemanticMemory()
    # We query rules based on the bug description and narrative to find relevant architectural constraints
    query_text = f"{request.bug_description}. {request.investigation_result.narrative}"
    try:
        facts = await memory.retrieve_facts(request.repo_path, query_text, top_k=3)
        architectural_rules = "\n".join([f"- {f['fact']} (Confidence: {f['confidence']})" for f in facts])
    except Exception as e:
        logger.warning(f"Could not retrieve semantic memory: {e}")
        architectural_rules = "No specific architectural rules found."

    # 2. Fetch base prompt from the dynamic prompt registry, then formulate the full prompt
    base_prompt, prompt_version = await fetch_prompt_versioned(
        "planner",
        "You are the GitOracle Planner Agent. An Investigator Agent has found the likely root cause of a bug. "
        "Your job is to generate a strict, constrained action plan for the Fixer Agent to execute."
    )
    await report_prompt_version(request.job_id, "planner", prompt_version)
    prompt_text = f"""
{base_prompt}

Bug Description: {request.bug_description}

Investigator Findings:
Narrative: {request.investigation_result.narrative}
Top Suspect Commit: {request.investigation_result.ranked_causes[0].message if request.investigation_result.ranked_causes else 'None'}
Affected Files: {', '.join(request.investigation_result.affected_files)}
Investigator's Recommended Strategy: {request.investigation_result.recommended_strategy}

Architectural Rules for this Repository (MUST OBEY):
{architectural_rules}

Generate a strict plan. You must pick exactly one FixStrategy.
Be very specific about which functions need to change.
Constrain the Fixer Agent by setting 'max_lines_to_change' to the absolute minimum needed.
"""
    messages = [
        {"role": "system", "content": "You are a senior software architect AI. You write strict plans to fix bugs without violating repository rules."},
        {"role": "user", "content": prompt_text}
    ]

    # 3. Call LLM (Layer 3b)
    try:
        result = await llm_structured(
            messages=messages,
            output_schema=PlannerOutput,
            max_tokens=1024,
            temperature=0.1,  # Lower temperature for strict planning
            job_id=request.job_id,
            agent_name="planner_agent"
        )
        return result
    except Exception as e:
        logger.error(f"Planning failed: {e}")
        raise HTTPException(status_code=500, detail=str(e))

# ==========================================
# Phase 3: Real Kafka Event Integration
# ==========================================
import asyncio
from shared.kafka_consumer import KafkaEventConsumer
from shared.kafka_producer import KafkaEventProducer

async def handle_planner_job(payload: dict):
    logger.info(f"Received Kafka event for planning: {payload}")
    
    # 1. Parse Kafka payload
    job_id = payload.get("job_id", "unknown-job")
    repo_path = payload.get("repo_path", "")
    error_id = payload.get("error_id", "unknown")
    human_instructions = payload.get("human_instructions") or ""
    target_repo = payload.get("target_repo") or ""
    branch = payload.get("branch") or ""
    
    # 2. Extract Investigation Result from payload
    investigation_dict = payload.get("investigation_result", {})
    try:
        investigation = InvestigationResult(**investigation_dict)
    except Exception as e:
        logger.error(f"Failed to parse investigation_result: {e}")
        # Fallback to a mock if the pipeline is incomplete or data is missing
        investigation = InvestigationResult(
            ranked_causes=[],
            narrative="Fallback: Missing or invalid investigation data from previous step.",
            confidence_score=0.0,
            affected_files=[],
            recommended_strategy="surgical_patch"
        )
    
    # The bug description was hardcoded to a generic "Error <id> occurred in the
    # system." string, which told the planner nothing it couldn't already infer from
    # the investigation narrative — and actively discarded the user's own words on
    # dashboard-triggered jobs. Prefer the explicit instruction, then the
    # investigation's narrative, and only then the generic fallback.
    if human_instructions:
        bug_description = human_instructions
    elif investigation.narrative:
        bug_description = investigation.narrative
    else:
        bug_description = f"Error {error_id} occurred in the system."

    request = PlannerRequest(
        tenant_id="00000000-0000-0000-0000-000000000000",
        repo_path=repo_path,
        bug_description=bug_description,
        investigation_result=investigation,
        job_id=job_id
    )
    
    try:
        # 2. Execute AI Planning
        plan = await create_plan(request)
        logger.info(f"Generated Plan for job {job_id}: {plan.strategy}")
        
        # 3. Publish to next step (Fixer)
        producer = KafkaEventProducer()
        fix_payload = {
            "job_id": job_id,
            "repo_url": payload.get("repo_url", ""),
            "repo_path": repo_path,
            "plan": plan.dict(),
            # Final hop: the Fixer reads human_instructions and injects it as a
            # highest-priority constraint, and target_repo determines which GitHub
            # repo the PR is opened against.
            "human_instructions": human_instructions,
            "target_repo": target_repo,
            "branch": branch,
        }
        await producer.publish("job.events.fix", fix_payload)
        logger.info(f"Published job.events.fix for job {job_id}")
    except Exception as e:
        # Same gap as the investigator: a job sits in TESTING/INVESTIGATING forever
        # if planning throws, because nothing downstream ever runs to move it on.
        logger.error(f"Planning failed for job {job_id}: {e}")
        try:
            await KafkaEventProducer().publish("job-escalated", {
                "jobId": job_id,
                "reason": f"Planner Agent failed: {e}",
                "confidenceScore": float(getattr(investigation, "confidence_score", 0.0) or 0.0),
            })
            logger.info(f"Published job-escalated for job {job_id}")
        except Exception as publish_error:
            logger.error(f"Could not escalate job {job_id}: {publish_error}")

@app.on_event("startup")
async def startup_event():
    # Start the Kafka consumer in a background task
    consumer = KafkaEventConsumer(topic="job.events.plan", group_id="planner-agent-group")
    asyncio.create_task(consumer.consume(handle_planner_job))

if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=9007)
