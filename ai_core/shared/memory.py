import os
import json
import httpx
import logging
import asyncpg
from pgvector.asyncpg import register_vector
from typing import List, Dict, Optional
from uuid import uuid4
from dotenv import load_dotenv, find_dotenv
from qdrant_client import AsyncQdrantClient
from qdrant_client.http import models as qmodels

# Load env variables globally
load_dotenv(find_dotenv())

logger = logging.getLogger(__name__)

# Fallback to local Ollama API for embeddings
OLLAMA_API_URL = os.environ.get("LLM_BASE_URL", "http://localhost:11434/v1/chat/completions")
OLLAMA_EMBED_URL = OLLAMA_API_URL.replace("/v1/chat/completions", "/api/embeddings")
EMBEDDING_MODEL = "all-minilm"

async def embed_text(content: str) -> List[float]:
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
            
        logger.info(f"[Episodic] Generating embedding for memory: '{content[:50]}...'")
        embedding = await embed_text(content)
        
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
        logger.info(f"[Episodic] Recalling memories similar to: '{query}'")
        q_embedding = await embed_text(query)
        
        conn = await get_db_connection()
        try:
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


class SemanticMemory:
    """
    Semantic Memory for GitOracle agents.
    Stores absolute truths and conventions about the repository in Qdrant.
    """
    def __init__(self):
        qdrant_host = os.environ.get("QDRANT_HOST", "localhost")
        qdrant_port = int(os.environ.get("QDRANT_HTTP_PORT", "6333"))
        self.client = AsyncQdrantClient(host=qdrant_host, port=qdrant_port)
        self.collection_name = "repo-knowledge"

    async def initialize_collection(self):
        """Ensure the collection exists in Qdrant."""
        exists = await self.client.collection_exists(self.collection_name)
        if not exists:
            logger.info(f"Creating Qdrant collection: {self.collection_name}")
            await self.client.create_collection(
                collection_name=self.collection_name,
                vectors_config=qmodels.VectorParams(
                    size=384,  # all-minilm size
                    distance=qmodels.Distance.COSINE
                )
            )

    async def learn_fact(self, repo: str, fact: str, source: str, confidence: float = 1.0):
        """Upsert a new semantic fact into Qdrant."""
        await self.initialize_collection()
        
        logger.info(f"[Semantic] Learning new fact for {repo}: '{fact[:50]}...'")
        embedding = await embed_text(fact)
        
        await self.client.upsert(
            collection_name=self.collection_name,
            points=[
                qmodels.PointStruct(
                    id=str(uuid4()),
                    vector=embedding,
                    payload={
                        "repo": repo,
                        "fact": fact,
                        "source": source,
                        "confidence": confidence
                    }
                )
            ]
        )

    async def retrieve_facts(self, repo: str, query: str, top_k: int = 3) -> List[Dict]:
        """Search Qdrant for facts relevant to the query, filtered by repo."""
        await self.initialize_collection()
        
        logger.info(f"[Semantic] Retrieving facts for {repo} similar to: '{query}'")
        query_vector = await embed_text(query)
        
        search_result = await self.client.search(
            collection_name=self.collection_name,
            query_vector=query_vector,
            query_filter=qmodels.Filter(
                must=[
                    qmodels.FieldCondition(
                        key="repo",
                        match=qmodels.MatchValue(value=repo)
                    )
                ]
            ),
            limit=top_k
        )
        
        facts = []
        for scored_point in search_result:
            facts.append({
                "fact": scored_point.payload.get("fact"),
                "source": scored_point.payload.get("source"),
                "confidence": scored_point.payload.get("confidence"),
                "score": scored_point.score  # Cosine similarity score
            })
            
        return facts
