import os
from qdrant_client import AsyncQdrantClient
from qdrant_client.http.models import PointStruct, VectorParams, Distance

class QdrantStore:
    def __init__(self, collection_name="codebase_memory"):
        url = os.getenv("QDRANT_URL", "http://localhost:6333")
        self.client = AsyncQdrantClient(url=url)
        self.collection_name = collection_name
        
    async def init_collection(self):
        """Creates the collection if it does not exist."""
        collections = await self.client.get_collections()
        if self.collection_name not in [c.name for c in collections.collections]:
            await self.client.create_collection(
                collection_name=self.collection_name,
                vectors_config=VectorParams(size=1536, distance=Distance.COSINE)
            )
            
    async def search(self, query_vector: list[float], limit: int = 5):
        """Perform semantic search on the Qdrant DB."""
        return await self.client.search(
            collection_name=self.collection_name,
            query_vector=query_vector,
            limit=limit
        )
