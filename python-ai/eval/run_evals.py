import json
import os
from pathlib import Path
from pydantic import BaseModel
import asyncio

# The Structured Output the LLM must return
class JudgeOutput(BaseModel):
    score: int
    reasoning: str

class EvalResult(BaseModel):
    case_id: str
    cause_correct: bool
    patch_score: float

class EvalHarness:
    def __init__(self, golden_dir: str = "golden"):
        self.golden_dir = Path(__file__).parent / golden_dir

    async def run_case(self, case_id: str) -> EvalResult:
        print(f"[{case_id}] Loading golden dataset...")
        case_path = self.golden_dir / case_id
        
        with open(case_path / "expected_root_commit.txt") as f:
            expected_root_commit = f.read().strip()
            
        with open(case_path / "expected_fix.patch") as f:
            expected_fix = f.read().strip()
            
        print(f"[{case_id}] Simulating GitOracle Agent Pipeline run...")
        # Mocking the AI pipeline output for testing purposes
        agent_root_commit = "a3f9b1c2e4d7a8b9c0d1e2f3a4b5c6d7e8f9a0b1"
        agent_patch = "--- src/main/java/com/example/UserService.java\n+++ src/main/java/com/example/UserService.java\n@@ -42,6 +42,9 @@\n     }\n \n     public void processPayment(User user, PaymentInfo payment) {\n+        if (user == null) return;\n         String userId = user.getId();\n         paymentGateway.charge(userId, payment.getAmount());\n     }"

        # Metric 1: Root cause accuracy
        cause_correct = (agent_root_commit == expected_root_commit)
        print(f"[{case_id}] Root Cause Correct: {cause_correct}")

        # Metric 2: Patch semantic correctness (LLM-as-judge)
        judge_prompt = f"""
        Expected fix:
        {expected_fix}
        
        Agent fix:
        {agent_patch}
        
        Rate the agent's fix on a scale 0-10:
        - 10: Identical or functionally equivalent to expected fix
        - 7-9: Correct fix, different approach
        - 4-6: Partially correct, some issues
        - 0-3: Incorrect or dangerous
        """
        
        print(f"[{case_id}] Sending to LLM for grading...")
        
        # MOCK: In production, this would call llm_structured() to Llama.cpp 
        # We simulate the LLM returning a structured Pydantic object
        judge_result = JudgeOutput(
            score=7,
            reasoning="The agent correctly checked for null, but returned silently instead of throwing an IllegalArgumentException. This prevents the crash but changes business logic."
        )
        
        print(f"[{case_id}] LLM Judge Score: {judge_result.score}/10")
        print(f"[{case_id}] LLM Reasoning: {judge_result.reasoning}")
        
        return EvalResult(
            case_id=case_id,
            cause_correct=cause_correct,
            patch_score=judge_result.score / 10.0
        )

    async def run_all(self):
        print("Starting GitOracle Automated Evaluation...")
        cases = [d.name for d in self.golden_dir.iterdir() if d.is_dir()]
        
        results = []
        for case in cases:
            res = await self.run_case(case)
            results.append(res)
            
        print("\n=== Evaluation Summary ===")
        avg_score = sum(r.patch_score for r in results) / len(results)
        print(f"Total Cases: {len(results)}")
        print(f"Average Patch Score: {avg_score * 100}%")

if __name__ == "__main__":
    harness = EvalHarness()
    asyncio.run(harness.run_all())
