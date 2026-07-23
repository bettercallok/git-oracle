import os
from langfuse import Langfuse
from langfuse.decorators import observe

# Initialize Langfuse
# In production, these come from environment variables.
# We explicitly configure it to point to our local docker-compose Langfuse instance.
os.environ["LANGFUSE_SECRET_KEY"] = "sk-lf-1234567890"
os.environ["LANGFUSE_PUBLIC_KEY"] = "pk-lf-1234567890"
os.environ["LANGFUSE_HOST"] = "http://localhost:3002"

langfuse = Langfuse()

class PlannerAgent:
    def __init__(self):
        print("Initializing Planner Agent with Langfuse tracking...")

    @observe()
    def generate_fix_plan(self, job_id: str, error_payload: str) -> str:
        print(f"Generating fix plan for job {job_id}...")
        
        # MOCK LLM CALL
        prompt = f"Fix this error: {error_payload}"
        output = "--- a/file.py\n+++ b/file.py\n@@ -1,1 +1,2 @@\n- null_var\n+ if null_var is None: return"
        
        # Track the mocked token usage in Langfuse manually (since we aren't using OpenAI directly here)
        langfuse.generation(
            name="planner_fix_generation",
            model="Qwen2.5-Coder",
            prompt=prompt,
            completion=output,
            usage={
                "promptTokens": 150,
                "completionTokens": 50,
                "totalTokens": 200
            }
        )
        
        # Ensure the event flushes to the local Langfuse container
        langfuse.flush()
        
        print("Plan generated and token usage tracked in Langfuse!")
        return output

if __name__ == "__main__":
    agent = PlannerAgent()
    agent.generate_fix_plan("job-555", "NullPointerException in UserService.java at line 42")
