import os
import sys
from contextlib import asynccontextmanager
from fastapi import Depends, FastAPI, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel
import asyncpg
import redis.asyncio as redis
import uvicorn

# Ensure ai_core modules can be imported
sys.path.append(os.path.dirname(os.path.dirname(os.path.dirname(__file__))))
from shared.internal_auth import require_internal_token

# Build the connection string from the same POSTGRES_* env vars the other agents
# use (see ai_core/shared/memory.py), so a single .env password works everywhere.
# DATABASE_URL, if explicitly set, still takes precedence.
def _default_database_url() -> str:
    user = os.getenv("POSTGRES_USER", "gitOracle")
    password = os.getenv("POSTGRES_PASSWORD", "GitOracle_PG_2025")
    host = os.getenv("POSTGRES_HOST", "localhost")
    port = os.getenv("POSTGRES_PORT", "5433")
    db = os.getenv("POSTGRES_DB", "gitOracle")
    return f"postgresql://{user}:{password}@{host}:{port}/{db}"

def _default_redis_url() -> str:
    host = os.getenv("REDIS_HOST", "localhost")
    port = os.getenv("REDIS_PORT", "6379")
    password = os.getenv("REDIS_PASSWORD", "")
    auth = f":{password}@" if password else ""
    return f"redis://{auth}{host}:{port}"

DATABASE_URL = os.getenv("DATABASE_URL") or _default_database_url()
REDIS_URL = os.getenv("REDIS_URL") or _default_redis_url()

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

app = FastAPI(title="GitOracle Prompt Registry", lifespan=lifespan, dependencies=[Depends(require_internal_token)])
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

class PromptVersion(BaseModel):
    content: str
    version: int | None = None

@app.get("/prompts/{agent}/{key}")
async def get_prompt(agent: str, key: str) -> str:
    """Returns just the prompt text (unchanged contract for existing callers)."""
    content, _version = await _resolve_active_prompt(agent, key)
    return content


@app.get("/prompts/{agent}/{key}/active")
async def get_active_prompt(agent: str, key: str) -> dict:
    """Returns the prompt text *and* the version number that produced it.

    Callers need the version to attribute a job's outcome and token spend back to
    the specific prompt revision that generated it — without this, per-version
    performance can't be measured at all.
    """
    content, version = await _resolve_active_prompt(agent, key)
    return {"content": content, "version": version}


async def _resolve_active_prompt(agent: str, key: str) -> tuple[str, int | None]:
    cache_key = f"prompt:{agent}:{key}"
    cached = await redis_client.get(cache_key)
    if cached:
        # Cache the version alongside the content so a cache hit can still
        # attribute usage; older cache entries without it simply miss on version.
        cached_version = await redis_client.get(f"{cache_key}:version")
        return cached.decode("utf-8"), int(cached_version) if cached_version else None

    async with db_pool.acquire() as conn:
        row = await conn.fetchrow(
            "SELECT content, version FROM prompt_version "
            "WHERE agent_name=$1 AND prompt_key=$2 AND is_active=true",
            agent, key
        )
        if not row:
            raise HTTPException(status_code=404, detail="Active prompt not found")

        content, version = row["content"], row["version"]
        await redis_client.set(cache_key, content, ex=300)  # 5 min cache
        await redis_client.set(f"{cache_key}:version", str(version), ex=300)
        return content, version

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
            
        # Hot-reload: invalidate cache. Must drop the version key too, or callers
        # keep attributing their work to the previously active version.
        cache_key = f"prompt:{agent}:{key}"
        await redis_client.delete(cache_key, f"{cache_key}:version")
        
    return {"status": "activated", "version": version}

if __name__ == "__main__":
    uvicorn.run(app, host=os.getenv("BIND_HOST", "127.0.0.1"), port=9005)
