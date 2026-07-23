import asyncio
import logging
from fastapi.testclient import TestClient
import sys
import os

sys.path.append(os.path.dirname(__file__))
from agents.reviewer.main import app

logging.basicConfig(level=logging.INFO)
client = TestClient(app)

def run_reviewer_test():
    print("🧐 Layer 5 — Agent 4: Reviewer Test")
    print("─" * 55)
    
    request_data = {
        "tenant_id": "tenant-xyz",
        "repo_path": "git-oracle/test-repo",
        "bug_description": "User login is failing sporadically",
        "fixer_patch": "--- auth.py\n+++ auth.py\n@@ -10,2 +10,2 @@\n-    cursor.execute(f'SELECT * FROM users WHERE email = {email}')\n+    cursor.execute(f'SELECT * FROM users WHERE email = \"{email}\"')",
        "human_comment": "This patch introduces a massive SQL injection vulnerability! You cannot just wrap the email in quotes, you must use parameterized queries.",
        "job_id": "test-review-1"
    }
    
    print("1️⃣ Sending human code review comment to Agent 4...")
    print("2️⃣ Agent is evaluating whether to defend or acknowledge...")
    response = client.post("/review", json=request_data)
    
    if response.status_code == 200:
        result = response.json()
        print(f"\n✅ Review Processed Successfully!")
        print(f"Agent's Stance: {result['stance']}")
        print(f"Concern Severity: {result['concern_severity']}")
        print(f"Action Needed (Trigger Re-Fix?): {result['action_needed']}")
        print(f"\n🗣️ Agent's Reply:\n{result['reply']}")
    else:
        print(f"❌ Failed: {response.status_code} - {response.text}")
        sys.exit(1)

if __name__ == "__main__":
    run_reviewer_test()
