import logging
from shared.memory import AgentMemory, get_db_connection

logger = logging.getLogger(__name__)

async def process_feedback(job_id: str, outcome: str):
    """
    Process RLHF feedback received from the GitHub Bot.
    outcome should be one of: 'PR_MERGED', 'REVIEW_APPROVED', 'PR_CLOSED', 'COMMIT_REVERTED'
    """
    logger.info(f"Processing feedback for job {job_id}: {outcome}")
    
    conn = await get_db_connection()
    try:
        # Fetch the job details to understand what the agent attempted
        job = await conn.fetchrow(
            "SELECT tenant_id, repo, state, fix_strategy, error_id "
            "FROM agent_job WHERE id = $1", 
            job_id
        )
        
        if not job:
            logger.warning(f"Job {job_id} not found in database. Cannot process feedback.")
            return

        tenant_id = str(job["tenant_id"])
        repo = job["repo"]
        strategy = job["fix_strategy"] or "unknown_strategy"
        error_id = job["error_id"] or "unknown_error"

        memory = AgentMemory()

        if outcome in ("PR_MERGED", "REVIEW_APPROVED"):
            # Reinforce: store successful fix as high-confidence memory
            content = (
                f"[CONFIRMED FIX] Successfully fixed error {error_id}. "
                f"Strategy: {strategy}. Reviewer accepted."
            )
            await memory.remember(
                tenant_id=tenant_id,
                repo=repo,
                memory_type="episodic",
                content=content,
                metadata={"confidence": 1.0, "feedback": outcome, "job_id": job_id}
            )
            logger.info(f"Stored positive reinforcement memory for job {job_id}")
            
        elif outcome in ("PR_CLOSED", "COMMIT_REVERTED"):
            # Negative signal: mark this pattern as risky
            content = (
                f"[FAILED FIX] Attempted {strategy} for error {error_id}. "
                f"PR was rejected/reverted. Do NOT repeat this exact approach."
            )
            await memory.remember(
                tenant_id=tenant_id,
                repo=repo,
                memory_type="episodic",
                content=content,
                metadata={"confidence": 0.0, "feedback": outcome, "job_id": job_id}
            )
            logger.info(f"Stored negative reinforcement memory for job {job_id}")

        else:
            logger.warning(f"Unknown feedback outcome: {outcome}")

    except Exception as e:
        logger.error(f"Error processing feedback for job {job_id}: {e}")
    finally:
        await conn.close()
