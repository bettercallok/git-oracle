import asyncio
import uuid
import requests
import sys
import os

# Ensure ai_core is in path so we can import shared modules
sys.path.append(os.path.join(os.path.dirname(__file__), '..', 'ai_core'))

from shared.memory import AgentMemory

async def test_episodic_memory():
    tenant_id = "00000000-0000-0000-0000-000000000000"
    repo_path = "/tmp/test-episodic-memory-repo"
    job_id = "mock-test-" + str(uuid.uuid4())
    
    # 1. Seed a highly specific memory
    memory = AgentMemory()
    bug_desc = "JSON parse error in metadata service due to malformed string."
    episode = "When handling JSON decoding errors in Python, you MUST wrap the parser in a try...except block and return `{'fallback': True}`. Do not do anything else."
    
    print("Seeding episodic memory...")
    await memory.remember(
        tenant_id=tenant_id,
        repo=repo_path,
        memory_type="episodic",
        content=f"Fix for: {bug_desc}. Solution: {episode}",
        metadata={"quality_score": 1.0, "source": "test_script"}
    )
    print("Memory seeded.")
    
    # Create the dummy file so the fixer doesn't fail trying to read it
    os.makedirs(repo_path, exist_ok=True)
    with open(os.path.join(repo_path, "parser.py"), "w") as f:
        f.write("import json\n\ndef parse_json(data):\n    return json.loads(data)\n")
    
    # 2. Trigger Fixer Agent
    payload = {
        "job_id": job_id,
        "tenant_id": tenant_id,
        "repo_path": repo_path,
        "bug_description": bug_desc,
        "human_instructions": "",
        "plan": {
            "strategy": "Update parse_json in parser.py to handle errors.",
            "affected_files": ["parser.py"],
            "affected_functions": ["parse_json"],
            "max_lines_to_change": 10,
            "reasoning": "We are failing to parse JSON from the client.",
            "confidence": 0.95
        }
    }
    
    print(f"Triggering Fixer Agent for job {job_id}...")
    try:
        response = requests.post("http://127.0.0.1:9002/fix", json=payload, timeout=120)
        response.raise_for_status()
    except Exception as e:
        print(f"Error calling Fixer Agent: {e}")
        sys.exit(1)
        
    result = response.json()
    patch_obj = result.get("patch") or {}
    fix_diff = patch_obj.get("diff", "")
    
    print("\n--- GENERATED FIX ---")
    print(fix_diff)
    print("---------------------\n")
    
    # 3. Assert the Fixer used the memory
    if "{'fallback': True}" in fix_diff or '{"fallback": True}' in fix_diff or '{"fallback": true}' in fix_diff or "{'fallback': true}" in fix_diff:
        print("✅ PASS: Fixer Agent successfully recalled and applied episodic memory!")
        sys.exit(0)
    else:
        print("❌ FAIL: Fixer Agent did NOT apply episodic memory instruction.")
        sys.exit(1)

if __name__ == "__main__":
    asyncio.run(test_episodic_memory())
