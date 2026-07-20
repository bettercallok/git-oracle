import asyncio
import logging
from fastapi.testclient import TestClient
import sys
import os

sys.path.append(os.path.dirname(__file__))
from agents.planner.main import app

logging.basicConfig(level=logging.INFO)
client = TestClient(app)

def run_planner_test():
    print("🧠 Layer 5 — Agent 2: Planner Test")
    print("─" * 55)
    
    # We will point it at its own repository
    repo_path = "git-oracle/test-repo"
    
    # This is the mock output that Agent 1 would have generated
    investigation_result = {
        "ranked_causes": [
            {
                "commit_sha": "abc1234",
                "author": "John Doe",
                "message": "Update requirements.txt to bump dependencies",
                "causal_effect_score": 0.95,
                "reasoning": "The commit removed the qdrant-client dependency which is required by the memory module."
            }
        ],
        "narrative": "The bug is caused by a missing dependency. The qdrant-client was accidentally removed in a recent commit, causing the vector database connection to fail.",
        "confidence_score": 0.95,
        "affected_files": ["ai_core/requirements.txt"],
        "recommended_strategy": "Add qdrant-client back to requirements.txt"
    }
    
    request_data = {
        "tenant_id": "tenant-xyz",
        "repo_path": repo_path,
        "bug_description": "ModuleNotFoundError: No module named 'qdrant_client'",
        "investigation_result": investigation_result,
        "job_id": "test-plan-1"
    }
    
    print("1️⃣ Sending planning request to Agent 2...")
    response = client.post("/plan", json=request_data)
    
    if response.status_code == 200:
        result = response.json()
        print("\n✅ Planning Completed Successfully!")
        print(f"Strategy Selected: {result['strategy']}")
        print(f"Confidence: {result['confidence']}")
        print(f"Max Lines Allowed to Change: {result['max_lines_to_change']}")
        print(f"Files to Touch: {', '.join(result['affected_files'])}")
        print(f"Functions to Touch: {', '.join(result['affected_functions'])}")
        print(f"\n💡 Architect's Reasoning:\n{result['reasoning']}")
    else:
        print(f"❌ Failed: {response.status_code} - {response.text}")
        sys.exit(1)

if __name__ == "__main__":
    run_planner_test()
