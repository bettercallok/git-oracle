import asyncio
import logging
import sys
import os

sys.path.append(os.path.dirname(__file__))

from shared.memory import AgentMemory, get_db_connection

logging.basicConfig(level=logging.INFO)

async def setup_test_tenant():
    """Retrieve the 'local-dev' tenant ID from Postgres for testing."""
    conn = await get_db_connection()
    try:
        tenant_id = await conn.fetchval("SELECT id FROM tenants WHERE org_name = 'local-dev'")
        if not tenant_id:
            tenant_id = await conn.fetchval(
                "INSERT INTO tenants (org_name, config) VALUES ('local-dev', '{}') RETURNING id"
            )
        return str(tenant_id)
    finally:
        await conn.close()

async def run_memory_test():
    print("🧠 Layer 4a — Episodic Memory Semantic Search Test")
    print("─" * 55)
    
    tenant_id = await setup_test_tenant()
    repo = "git-oracle/test-repo"
    memory_type = "episodic"
    
    memory = AgentMemory()
    
    # 1. Store memories
    print("1️⃣ Storing past experiences...")
    memories_to_store = [
        "Fixed a NullPointerException in the PaymentService by adding Optional.ofNullable()",
        "Updated the Redis cache timeout from 5 seconds to 30 seconds because of high latency",
        "Refactored the Postgres connection pool to use HikariCP for better performance",
        "Resolved an issue where the billing webhooks were failing due to missing signatures"
    ]
    
    for m in memories_to_store:
        await memory.remember(tenant_id, repo, memory_type, m, metadata={"type": "test"})
        
    print("\n2️⃣ Recalling relevant memories (Semantic Search for 'billing crash')...")
    
    results = await memory.recall(tenant_id, repo, "billing crash", memory_type, top_k=2)
    
    for i, res in enumerate(results):
        print(f"\nResult {i+1} (Distance: {res['distance']:.4f}):")
        print(f"Content: {res['content']}")

if __name__ == "__main__":
    asyncio.run(run_memory_test())
