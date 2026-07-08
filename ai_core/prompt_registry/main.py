import os
import json
import logging
from typing import Optional, List
from fastapi import FastAPI, HTTPException, status
from pydantic import BaseModel
import asyncpg
import redis.asyncio as redis
from contextlib import asynccontextmanager

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

# Config
DATABASE_URL = os.getenv("DATABASE_URL", "postgresql://gitOracle:GitOracle_PG_2025@localhost:5433/gitOracle")
REDIS_URL = os.getenv("REDIS_URL", "redis://:GitOracle_Redis_2025@localhost:6379/0")

class PromptVersionCreate(BaseModel):
    content: str
    changelog: Optional[str] = None
    created_by: str = "system"

class PromptVersionResponse(BaseModel):
    id: str
    agent_name: str
    prompt_key: str
    version: int
    content: str
    is_active: bool
    eval_score: Optional[float] = None
    created_at: str

# Global state
app_state = {}

@asynccontextmanager
async def lifespan(app: FastAPI):
    # Startup
    logger.info("Connecting to PostgreSQL...")
    app_state["db_pool"] = await asyncpg.create_pool(DATABASE_URL)
    
    logger.info("Connecting to Redis...")
    app_state["redis"] = redis.from_url(REDIS_URL, decode_responses=True)
    
    yield
    
    # Shutdown
    logger.info("Closing connections...")
    await app_state["redis"].close()
    await app_state["db_pool"].close()

app = FastAPI(title="GitOracle Prompt Registry", lifespan=lifespan)


def get_cache_key(agent: str, key: str) -> str:
    return f"prompt:{agent}:{key}:active"


@app.get("/prompts/{agent}/{key}", response_model=PromptVersionResponse)
async def get_active_prompt(agent: str, key: str):
    """
    Fetch the currently active prompt for an agent.
    Checks Redis first, falls back to Postgres.
    """
    redis_client: redis.Redis = app_state["redis"]
    cache_key = get_cache_key(agent, key)
    
    # 1. Check Redis Cache
    cached_prompt = await redis_client.get(cache_key)
    if cached_prompt:
        logger.info(f"Cache HIT for {cache_key}")
        return json.loads(cached_prompt)
        
    # 2. Fallback to DB
    logger.info(f"Cache MISS for {cache_key}. Querying DB...")
    pool: asyncpg.Pool = app_state["db_pool"]
    
    query = """
        SELECT id, agent_name, prompt_key, version, content, is_active, eval_score, created_at
        FROM prompt_version
        WHERE agent_name = $1 AND prompt_key = $2 AND is_active = true
        ORDER BY version DESC
        LIMIT 1
    """
    
    async with pool.acquire() as conn:
        record = await conn.fetchrow(query, agent, key)
        
    if not record:
        raise HTTPException(status_code=404, detail="Active prompt not found")
        
    result = dict(record)
    result["id"] = str(result["id"])
    result["created_at"] = result["created_at"].isoformat()
    
    # 3. Save to Cache
    await redis_client.set(cache_key, json.dumps(result))
    
    return result


@app.post("/prompts/{agent}/{key}/version", response_model=PromptVersionResponse, status_code=status.HTTP_201_CREATED)
async def create_prompt_version(agent: str, key: str, data: PromptVersionCreate):
    """
    Create a new inactive version of a prompt.
    """
    pool: asyncpg.Pool = app_state["db_pool"]
    
    query_max_version = """
        SELECT COALESCE(MAX(version), 0) + 1 FROM prompt_version
        WHERE agent_name = $1 AND prompt_key = $2
    """
    
    insert_query = """
        INSERT INTO prompt_version (agent_name, prompt_key, version, content, changelog, created_by, is_active)
        VALUES ($1, $2, $3, $4, $5, $6, false)
        RETURNING id, agent_name, prompt_key, version, content, is_active, eval_score, created_at
    """
    
    async with pool.acquire() as conn:
        async with conn.transaction():
            new_version = await conn.fetchval(query_max_version, agent, key)
            record = await conn.fetchrow(insert_query, agent, key, new_version, data.content, data.changelog, data.created_by)
            
    result = dict(record)
    result["id"] = str(result["id"])
    result["created_at"] = result["created_at"].isoformat()
    return result


@app.put("/prompts/{agent}/{key}/activate/{version}")
async def activate_prompt_version(agent: str, key: str, version: int):
    """
    Switch the active version of a prompt and invalidate the Redis cache.
    """
    pool: asyncpg.Pool = app_state["db_pool"]
    redis_client: redis.Redis = app_state["redis"]
    
    # Transaction: Deactivate all, then activate the target version
    async with pool.acquire() as conn:
        async with conn.transaction():
            await conn.execute("UPDATE prompt_version SET is_active = false WHERE agent_name = $1 AND prompt_key = $2", agent, key)
            
            res = await conn.execute("UPDATE prompt_version SET is_active = true WHERE agent_name = $1 AND prompt_key = $2 AND version = $3", agent, key, version)
            
            if res == "UPDATE 0":
                raise HTTPException(status_code=404, detail="Version not found")
                
    # Invalidate Cache
    cache_key = get_cache_key(agent, key)
    await redis_client.delete(cache_key)
    logger.info(f"Invalidated cache for {cache_key}")
    
    return {"status": "success", "message": f"Activated version {version}"}


@app.get("/prompts/{agent}/{key}/versions", response_model=List[PromptVersionResponse])
async def list_prompt_versions(agent: str, key: str):
    """
    List all historical versions of a prompt.
    """
    pool: asyncpg.Pool = app_state["db_pool"]
    
    query = """
        SELECT id, agent_name, prompt_key, version, content, is_active, eval_score, created_at
        FROM prompt_version
        WHERE agent_name = $1 AND prompt_key = $2
        ORDER BY version DESC
    """
    
    async with pool.acquire() as conn:
        records = await conn.fetch(query, agent, key)
        
    results = []
    for r in records:
        d = dict(r)
        d["id"] = str(d["id"])
        d["created_at"] = d["created_at"].isoformat()
        results.append(d)
        
    return results
