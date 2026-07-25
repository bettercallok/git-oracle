import os
import json
from contextlib import asynccontextmanager
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel
import asyncpg
import redis.asyncio as redis
import uvicorn

DATABASE_URL = os.getenv("DATABASE_URL", "postgresql://gitOracle:gitOracle@localhost:5433/gitOracle")
REDIS_URL = os.getenv("REDIS_URL", "redis://localhost:6379")

# Global state
db_pool = None
redis_client = None

@asynccontextmanager
async def lifespan(app: FastAPI):
    global db_pool, redis_client
    db_pool = await asyncpg.create_pool(DATABASE_URL)
    redis_client = redis.from_url(REDIS_URL)
    yield
    await db_pool.close()
    await redis_client.close()

app = FastAPI(title="GitOracle Prompt Registry", lifespan=lifespan)

class PromptVersion(BaseModel):
    content: str
    version: int | None = None

@app.get("/prompts/{agent}/{key}")
async def get_prompt(agent: str, key: str) -> str:
    cache_key = f"prompt:{agent}:{key}"
    cached = await redis_client.get(cache_key)
    if cached:
        return cached.decode("utf-8")
    
    async with db_pool.acquire() as conn:
        row = await conn.fetchrow(
            "SELECT content FROM prompt_version WHERE agent_name=$1 AND prompt_key=$2 AND is_active=true",
            agent, key
        )
        if not row:
            raise HTTPException(status_code=404, detail="Active prompt not found")
            
        content = row["content"]
        await redis_client.set(cache_key, content, ex=300)  # 5 min cache
        return content

@app.post("/prompts/{agent}/{key}/version")
async def create_version(agent: str, key: str, prompt: PromptVersion):
    async with db_pool.acquire() as conn:
        # Get next version number
        row = await conn.fetchrow(
            "SELECT COALESCE(MAX(version), 0) + 1 as next_v FROM prompt_version WHERE agent_name=$1 AND prompt_key=$2",
            agent, key
        )
        next_v = row["next_v"]
        
        await conn.execute(
            "INSERT INTO prompt_version (agent_name, prompt_key, version, content, is_active) VALUES ($1, $2, $3, $4, false)",
            agent, key, next_v, prompt.content
        )
    return {"version": next_v, "status": "created"}

@app.put("/prompts/{agent}/{key}/activate/{version}")
async def activate_version(agent: str, key: str, version: int):
    async with db_pool.acquire() as conn:
        # Deactivate current
        await conn.execute(
            "UPDATE prompt_version SET is_active=false WHERE agent_name=$1 AND prompt_key=$2",
            agent, key
        )
        # Activate new
        result = await conn.execute(
            "UPDATE prompt_version SET is_active=true WHERE agent_name=$1 AND prompt_key=$2 AND version=$3",
            agent, key, version
        )
        if result == "UPDATE 0":
            raise HTTPException(status_code=404, detail="Version not found")
            
        # Hot-reload: invalidate cache
        cache_key = f"prompt:{agent}:{key}"
        await redis_client.delete(cache_key)
        
    return {"status": "activated", "version": version}

if __name__ == "__main__":
    uvicorn.run(app, host="0.0.0.0", port=9005)
