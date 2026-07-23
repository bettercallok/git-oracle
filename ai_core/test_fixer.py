import asyncio
import logging
from fastapi.testclient import TestClient
import sys
import os

sys.path.append(os.path.dirname(__file__))
from agents.fixer.main import app

logging.basicConfig(level=logging.INFO)
client = TestClient(app)

def run_fixer_test():
    print("🛠️ Layer 5 — Agent 3: Fixer Test (ReAct Loop)")
    print("─" * 55)
    
    # Pointing to the repo
    repo_path = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    
    # This is the mock output that Agent 2 (Planner) generated
    planner_output = {
        "strategy": "dependency_update",
        "affected_files": ["ai_core/requirements.txt"],
        "affected_functions": [],
        "max_lines_to_change": 1,
        "reasoning": "The qdrant-client dependency must be added back to requirements.txt to restore vector DB connectivity.",
        "confidence": 0.98
    }
    
    request_data = {
        "tenant_id": "tenant-xyz",
        "repo_path": repo_path,
        "bug_description": "ModuleNotFoundError: No module named 'qdrant_client'",
        "plan": planner_output,
        "job_id": "test-fix-1"
    }
    
    print("1️⃣ Sending strict blueprint to Fixer Agent...")
    print("2️⃣ Agent is entering ReAct loop (Reason + Act)...")
    response = client.post("/fix", json=request_data)
    
    if response.status_code == 200:
        result = response.json()
        if result["success"]:
            patch = result["patch"]
            print(f"\n✅ Fix Completed Successfully in {result['attempts']} attempt(s)!")
            print(f"Summary: {patch['summary']}")
            print(f"Confidence: {patch['confidence']}")
            print(f"Files touched: {', '.join(patch['files_modified'])}")
            print(f"\n📝 Unified Diff Generated:\n")
            print(patch['diff'])
        else:
            print(f"\n❌ Fix Failed after {result['attempts']} attempts.")
            print(f"Escalation Report: {result['escalation_report']}")
    else:
        print(f"❌ Failed: {response.status_code} - {response.text}")
        sys.exit(1)

if __name__ == "__main__":
    run_fixer_test()
