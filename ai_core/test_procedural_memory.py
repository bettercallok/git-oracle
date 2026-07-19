import asyncio
import logging
import sys
import os

sys.path.append(os.path.dirname(__file__))

from shared.memory import ProceduralMemory, RepoProcedures

logging.basicConfig(level=logging.INFO)

async def run_procedural_test():
    print("🧠 Layer 4c — Procedural Memory (Redis) Test")
    print("─" * 55)
    
    tenant_id = "tenant-xyz-123"
    repo = "git-oracle/test-repo"
    memory = ProceduralMemory()
    
    # 1. Create and Save Procedures
    print("1️⃣ Storing procedural instructions...")
    procedures = RepoProcedures(
        test_command="mvn test -Pintegration",
        lint_command="mvn checkstyle:check",
        required_env_vars=["DB_HOST", "DB_USER", "DB_PASS"],
        setup_commands=["docker-compose up -d postgres"]
    )
    
    await memory.save_procedures(tenant_id, repo, procedures)
    print("✅ Successfully saved to Redis!")
    
    # 2. Retrieve Procedures
    print("\n2️⃣ Retrieving procedural instructions...")
    loaded = await memory.load_procedures(tenant_id, repo)
    
    if loaded:
        print(f"Test Command: {loaded.test_command}")
        print(f"Lint Command: {loaded.lint_command}")
        print(f"Required Env Vars: {', '.join(loaded.required_env_vars)}")
        print(f"Setup Commands: {', '.join(loaded.setup_commands)}")
        print("\n✅ Verification Successful: Pydantic deserialization worked perfectly!")
    else:
        print("❌ Failed to load procedures from Redis.")
        sys.exit(1)

if __name__ == "__main__":
    asyncio.run(run_procedural_test())
