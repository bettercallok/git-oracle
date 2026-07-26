from fastapi import FastAPI, Request, HTTPException
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

    # 2. Formulate Prompt
    prompt_text = f"""
You are the GitOracle Planner Agent. An Investigator Agent has found the likely root cause of a bug.
Your job is to generate a strict, constrained action plan for the Fixer Agent to execute.

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
    
    # In a full system, we would query the Investigator Agent here, but for now we mock it
    investigation = InvestigationResult(
        ranked_causes=[],
        narrative="NullPointerException during Webhook processing",
        confidence_score=0.9,
        affected_files=[],
        recommended_strategy="surgical_patch"
    )
    
    request = PlannerRequest(
        tenant_id="00000000-0000-0000-0000-000000000000",
        repo_path=repo_path,
        bug_description=f"Error {error_id} occurred in the system.",
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
            "repo_path": repo_path,
            "plan": plan.dict()
        }
        await producer.publish("job.events.fix", fix_payload)
        logger.info(f"Published job.events.fix for job {job_id}")
    except Exception as e:
        logger.error(f"Planning failed for job {job_id}: {e}")

@app.on_event("startup")
async def startup_event():
    # Start the Kafka consumer in a background task
    consumer = KafkaEventConsumer(topic="job.events.plan", group_id="planner-agent-group")
    asyncio.create_task(consumer.consume(handle_planner_job))

if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=9004)
