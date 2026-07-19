import asyncio
import logging
from fastapi.testclient import TestClient
import sys
import os

sys.path.append(os.path.dirname(__file__))
from agents.investigator.main import app

logging.basicConfig(level=logging.INFO)
client = TestClient(app)

def run_investigator_test():
    print("🕵️ Layer 5 — Agent 1: Investigator Test")
    print("─" * 55)
    
    # We will point it at its own repository to investigate!
    repo_path = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    
    request_data = {
        "tenant_id": "tenant-xyz",
        "repo_path": repo_path,
        "bug_description": "The Qdrant client is failing to connect due to a missing dependency.",
        "affected_files": ["ai_core/requirements.txt"],
        "job_id": "test-investigate-1"
    }
    
    print("1️⃣ Sending investigation request to Agent 1...")
    response = client.post("/investigate", json=request_data)
    
    if response.status_code == 200:
        result = response.json()
        print("\n✅ Investigation Completed Successfully!")
        print(f"Confidence Score: {result['confidence_score']}")
        print(f"Narrative: {result['narrative']}\n")
        
        print("🏆 Ranked Causes:")
        for idx, cause in enumerate(result['ranked_causes']):
            print(f"  {idx + 1}. Commit {cause['commit_sha'][:7]} by {cause['author']}")
            print(f"     Score: {cause['causal_effect_score']}")
            print(f"     Reason: {cause['reasoning']}")
            
        print(f"\n💡 Recommendation: {result['recommended_strategy']}")
    else:
        print(f"❌ Failed: {response.status_code} - {response.text}")
        sys.exit(1)

if __name__ == "__main__":
    run_investigator_test()
