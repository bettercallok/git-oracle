import os
from contextlib import asynccontextmanager
from typing import Optional, Dict

import asyncpg
from fastapi import FastAPI, HTTPException, Query
from pydantic import BaseModel, Field

# --- Database Setup ---

DATABASE_URL = os.getenv("DATABASE_URL", "postgresql://gitOracle:change_me_strong_password@localhost:5433/gitOracle")

# Global pool
db_pool: Optional[asyncpg.Pool] = None

@asynccontextmanager
async def lifespan(app: FastAPI):
    global db_pool
    # Initialize the connection pool
    try:
        db_pool = await asyncpg.create_pool(DATABASE_URL)
        print("Connected to PostgreSQL pool.")
    except Exception as e:
        print(f"Failed to connect to database: {e}")
        # Not exiting here so the app still starts (for healthchecks), but endpoints will fail.
    
    yield
    
    # Close the pool
    if db_pool:
        await db_pool.close()
        print("Closed PostgreSQL pool.")

app = FastAPI(title="GitOracle Prompt Registry", lifespan=lifespan)

# --- Models ---

class PromptResponse(BaseModel):
    id: str
    agent_name: str
    prompt_key: str
    version: int
    content: str
    eval_score: Optional[float] = None

# --- Endpoints ---

@app.get("/health")
async def health_check():
    if not db_pool:
        raise HTTPException(status_code=503, detail="Database pool not initialized")
    try:
        async with db_pool.acquire() as conn:
            await conn.execute("SELECT 1")
    except Exception as e:
        raise HTTPException(status_code=503, detail=f"Database connection error: {e}")
    return {"status": "ok"}

@app.get("/prompts/{agent_name}/{prompt_key}", response_model=PromptResponse)
async def get_prompt(
    agent_name: str,
    prompt_key: str,
    tenant_id: Optional[str] = Query(None, description="Optional tenant ID to get tenant-specific prompt overrides")
):
    """
    Fetch the active prompt version for a given agent and key.
    Prioritizes tenant-specific prompts over global defaults (where tenant_id IS NULL).
    """
    if not db_pool:
        raise HTTPException(status_code=503, detail="Database not ready")

    query = """
        SELECT id, agent_name, prompt_key, version, content, eval_score
        FROM prompt_version
        WHERE agent_name = $1
          AND prompt_key = $2
          AND is_active = true
          AND (tenant_id = $3 OR tenant_id IS NULL)
        ORDER BY 
          -- tenant-specific row comes first, then global fallback
          (tenant_id IS NOT NULL) DESC
        LIMIT 1;
    """
    
    try:
        # If tenant_id is provided, try to parse it to UUID in the DB, otherwise pass None
        # Using string for UUID is fine with asyncpg if we cast, but asyncpg handles string to UUID mapping natively usually.
        # However, to be safe, we just pass the string.
        async with db_pool.acquire() as conn:
            row = await conn.fetchrow(query, agent_name, prompt_key, tenant_id)
            
            if not row:
                raise HTTPException(status_code=404, detail="Active prompt not found")
            
            return PromptResponse(
                id=str(row["id"]),
                agent_name=row["agent_name"],
                prompt_key=row["prompt_key"],
                version=row["version"],
                content=row["content"],
                eval_score=row["eval_score"]
            )
    except asyncpg.PostgresError as e:
        raise HTTPException(status_code=500, detail=f"Database error: {e}")
    except Exception as e:
        if isinstance(e, HTTPException):
            raise e
        raise HTTPException(status_code=500, detail="Internal server error")

if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=int(os.getenv("PORT", 9005)))
