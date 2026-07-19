import os
import json
import httpx
import logging
import asyncpg
from pgvector.asyncpg import register_vector
from typing import List, Dict, Optional
from dotenv import load_dotenv, find_dotenv

# Load env variables globally
load_dotenv(find_dotenv())

logger = logging.getLogger(__name__)

# Fallback to local Ollama API for embeddings
OLLAMA_API_URL = os.environ.get("LLM_BASE_URL", "http://localhost:11434/v1/chat/completions")
# Convert chat completions URL to embedding endpoint
OLLAMA_EMBED_URL = OLLAMA_API_URL.replace("/v1/chat/completions", "/api/embeddings")
EMBEDDING_MODEL = "all-minilm"

async def get_db_connection():
    """Create an asyncpg connection and register pgvector types."""
    conn = await asyncpg.connect(
        host=os.environ.get("POSTGRES_HOST", "localhost"),
        port=os.environ.get("POSTGRES_PORT", "5433"),
        user=os.environ.get("POSTGRES_USER", "gitOracle"),
        password=os.environ.get("POSTGRES_PASSWORD", "GitOracle_PG_2025"),
        database=os.environ.get("POSTGRES_DB", "gitOracle")
    )
    await register_vector(conn)
    return conn

class AgentMemory:
    """
    Episodic Memory for GitOracle agents. 
    Stores experiences and recalls them using pgvector semantic similarity.
    """

    async def embed(self, content: str) -> List[float]:
        """Convert text into a 384-dimensional vector using local Ollama model."""
        async with httpx.AsyncClient(timeout=30.0) as client:
            response = await client.post(
                OLLAMA_EMBED_URL,
                json={
                    "model": EMBEDDING_MODEL,
                    "prompt": content
                }
            )
            response.raise_for_status()
            data = response.json()
            return data["embedding"]

    async def remember(
        self, 
        tenant_id: str, 
        repo: str, 
        memory_type: str, 
        content: str, 
        metadata: Optional[Dict] = None
    ) -> str:
        """Store a new memory in PostgreSQL."""
        if metadata is None:
            metadata = {}
            
        logger.info(f"Generating embedding for memory: '{content[:50]}...'")
        embedding = await self.embed(content)
        
        conn = await get_db_connection()
        try:
            mem_id = await conn.fetchval(
                """
                INSERT INTO agent_memory (tenant_id, repo, memory_type, content, embedding, metadata)
                VALUES ($1, $2, $3, $4, $5, $6)
                RETURNING id
                """,
                tenant_id, repo, memory_type, content, embedding, json.dumps(metadata)
            )
            logger.info(f"Memory stored successfully (ID: {mem_id})")
            return str(mem_id)
        finally:
            await conn.close()

    async def recall(
        self, 
        tenant_id: str, 
        repo: str, 
        query: str, 
        memory_type: str, 
        top_k: int = 3
    ) -> List[Dict]:
        """Retrieve most similar past memories using pgvector cosine distance (<=>)."""
        logger.info(f"Recalling memories similar to: '{query}'")
        q_embedding = await self.embed(query)
        
        conn = await get_db_connection()
        try:
            # We select content and confidence, ordered by cosine distance
            # The <=> operator computes cosine distance (0 means identical, 2 means exact opposite)
            rows = await conn.fetch(
                """
                SELECT id, content, confidence, metadata, (embedding <=> $1) AS distance
                FROM agent_memory
                WHERE tenant_id = $2 AND repo = $3 AND memory_type = $4
                ORDER BY distance ASC
                LIMIT $5
                """,
                q_embedding, tenant_id, repo, memory_type, top_k
            )
            
            memories = []
            for r in rows:
                memories.append({
                    "id": str(r["id"]),
                    "content": r["content"],
                    "confidence": r["confidence"],
                    "metadata": json.loads(r["metadata"]),
                    "distance": r["distance"]
                })
                
            return memories
        finally:
            await conn.close()
