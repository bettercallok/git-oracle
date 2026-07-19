import asyncio
import logging
import sys
import os

sys.path.append(os.path.dirname(__file__))

from shared.memory import SemanticMemory

logging.basicConfig(level=logging.INFO)

async def run_qdrant_test():
    print("🧠 Layer 4b — Semantic Memory (Qdrant) Search Test")
    print("─" * 55)
    
    repo = "git-oracle/test-repo"
    memory = SemanticMemory()
    
    # 1. Store semantic facts
    print("1️⃣ Learning architectural facts...")
    facts_to_store = [
        "UserService.processPayment() is the payment critical path. Null checks required on all inputs.",
        "Always use constructor injection with @RequiredArgsConstructor. Never use field injection.",
        "The Redis cache TTL for user sessions must be exactly 3600 seconds.",
        "Docker compose must be running before executing the integration tests via Pytest."
    ]
    
    for i, fact in enumerate(facts_to_store):
        await memory.learn_fact(repo, fact, source=f"manual-test-{i}")
        
    print("\n2️⃣ Retrieving facts (Semantic Search for 'how do I inject dependencies?')...")
    
    # Intentionally using a question that doesn't share keywords with the fact
    results = await memory.retrieve_facts(repo, "how do I inject dependencies?", top_k=1)
    
    for i, res in enumerate(results):
        print(f"\nResult {i+1} (Cosine Score: {res['score']:.4f}):")
        print(f"Fact: {res['fact']}")
        print(f"Source: {res['source']}")

if __name__ == "__main__":
    asyncio.run(run_qdrant_test())
