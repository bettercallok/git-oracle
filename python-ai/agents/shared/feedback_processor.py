import asyncio
from uuid import UUID
from memory import MemoryStore

memory = MemoryStore()

async def process_feedback(job_id: str, outcome: str):
    print(f"\nProcessing Feedback for Job: {job_id} -> {outcome}")
    
    # In production, we'd query PostgreSQL for the job details using this job_id
    # MOCK DB fetch:
    job = {
        "repo": "omkhatri/test-repo",
        "error_type": "NullPointerException",
        "fix_summary": "Added null check to UserService",
        "fix_strategy": "surgical_patch",
        "raw_payload": '{"error":"NPE"}'
    }
    
    if outcome in ("POSITIVE", "STRONG_POSITIVE"):
        # Reinforce: store successful fix as high-confidence memory
        narrative = (
            f"[CONFIRMED FIX] {job['fix_summary']}. Strategy: {job['fix_strategy']}. "
            f"Reviewer accepted without changes."
        )
        await memory.remember(
            job["repo"], 
            "episodic",
            narrative,
            metadata={"confidence": 1.0, "feedback": outcome}
        )
        
    elif outcome in ("NEGATIVE", "STRONG_NEGATIVE"):
        # Negative signal: mark this pattern as risky
        narrative = (
            f"[FAILED FIX] Attempted {job['fix_strategy']} for {job['error_type']}. "
            f"PR was rejected/reverted. Do NOT repeat this approach."
        )
        await memory.remember(
            job["repo"], 
            "episodic",
            narrative,
            metadata={"confidence": 0.0, "feedback": outcome}
        )
        
        # Optionally: trigger automatic re-investigation (Layer 9c)
        if outcome == "STRONG_NEGATIVE":
            print(f"STRONG NEGATIVE signal detected! Re-queueing job {job_id} for investigation...")
            # await kafka.send("error-ingested", job['raw_payload'])

if __name__ == "__main__":
    # Test the script locally
    asyncio.run(process_feedback("1234-abcd", "POSITIVE"))
    asyncio.run(process_feedback("5678-efgh", "STRONG_NEGATIVE"))
