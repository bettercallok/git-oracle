import json
import os
import time
import requests
from pathlib import Path
from pydantic import BaseModel
import asyncio
from dotenv import load_dotenv

# Load env variables from project root
load_dotenv(Path(__file__).parent.parent / ".env")

API_URL = "http://localhost:8083/api/v1"
LLM_BASE_URL = os.environ.get("LLM_BASE_URL", "http://127.0.0.1:8000/v1")
LLM_API_KEY = os.environ.get("LLM_API_KEY", "dummy-key")

class EvalResult(BaseModel):
    case_id: str
    cause_correct: bool
    patch_score: float
    latency_ms: int

class EvalHarness:
    def __init__(self, golden_dir: str = "golden"):
        self.golden_dir = Path(__file__).parent / golden_dir
        self.repo_url = "file:///tmp/gitoracle-eval-repo"

    def grade_patch(self, expected: str, actual: str) -> int:
        if not expected and not actual: return 10
        if not actual: return 0
        
        prompt = f"""
        You are an expert code reviewer. Grade the provided AI-generated fix against the expected golden fix.
        Expected fix:
        {expected}
        
        Agent fix:
        {actual}
        
        Rate the agent's fix on a scale 0-10:
        - 10: Identical or functionally equivalent to expected fix
        - 7-9: Correct fix, different approach
        - 4-6: Partially correct, some issues
        - 0-3: Incorrect or dangerous
        
        Return ONLY the integer score.
        """
        
        try:
            resp = requests.post(
                f"{LLM_BASE_URL}/chat/completions",
                headers={"Authorization": f"Bearer {LLM_API_KEY}"},
                json={
                    "model": "qwen2.5-coder",
                    "messages": [{"role": "user", "content": prompt}],
                    "temperature": 0.0,
                    "max_tokens": 10
                },
                timeout=30
            )
            resp.raise_for_status()
            content = resp.json()["choices"][0]["message"]["content"].strip()
            # Extract first number
            digits = ''.join(c for c in content if c.isdigit())
            return min(max(int(digits), 0), 10) if digits else 0
        except Exception as e:
            print(f"Error grading patch: {e}")
            return 0

    async def run_case(self, case_id: str) -> EvalResult:
        print(f"\n[{case_id}] Starting eval run...")
        case_path = self.golden_dir / case_id
        
        with open(case_path / "expected_root_commit.txt") as f:
            expected_root_commit = f.read().strip()
            
        with open(case_path / "expected_fix.patch") as f:
            expected_fix = f.read().strip()
            
        with open(case_path / "bug_description.txt") as f:
            issue_description = f.read().strip()

        start_time = time.time()
        
        # Trigger the pipeline
        print(f"[{case_id}] Triggering GitOracle pipeline...")
        resp = requests.post(f"{API_URL}/trigger", json={
            "repoUrl": self.repo_url,
            "issueDescription": issue_description
        })
        resp.raise_for_status()
        job_id = resp.json().get("jobId")
        
        # Poll for completion
        status = "QUEUED"
        agent_patch = ""
        agent_root_commit = ""
        
        print(f"[{case_id}] Waiting for job {job_id} to complete...")
        while status not in ["SUCCESS", "FAILED", "ESCALATED"]:
            time.sleep(2)
            try:
                job_resp = requests.get(f"{API_URL}/jobs/{job_id}")
                job_resp.raise_for_status()
                data = job_resp.json()
                status = data.get("state", "UNKNOWN")
                print(f"[{case_id}] Status: {status}", end="\r")
            except Exception as e:
                pass
                
        latency_ms = int((time.time() - start_time) * 1000)
        print(f"\n[{case_id}] Job finished with status {status} in {latency_ms}ms")
        
        if status == "SUCCESS":
            agent_root_commit = data.get("rootCommit", "")
            agent_patch = data.get("fixPatch", "")
            
        cause_correct = (agent_root_commit == expected_root_commit)
        print(f"[{case_id}] Root Cause Correct: {cause_correct} (Expected: {expected_root_commit[:8]}, Got: {agent_root_commit[:8]})")
        
        score = self.grade_patch(expected_fix, agent_patch)
        print(f"[{case_id}] LLM Judge Score: {score}/10")
        
        return EvalResult(
            case_id=case_id,
            cause_correct=cause_correct,
            patch_score=score / 10.0,
            latency_ms=latency_ms
        )

    async def run_all(self):
        print("Starting GitOracle Automated Evaluation...")
        cases = sorted([d.name for d in self.golden_dir.iterdir() if d.is_dir()])
        
        if not cases:
            print("No test cases found in golden dataset.")
            return
            
        results = []
        for case in cases:
            res = await self.run_case(case)
            results.append(res)
            
        print("\n=== Evaluation Summary ===")
        avg_score = sum(r.patch_score for r in results) / len(results)
        avg_latency = sum(r.latency_ms for r in results) // len(results)
        accuracy = sum(1 for r in results if r.cause_correct and r.patch_score >= 0.7) / len(results)
        
        print(f"Total Cases: {len(results)}")
        print(f"Average Patch Score: {avg_score * 100:.1f}%")
        print(f"Accuracy (Correct Cause & Score >= 7): {accuracy * 100:.1f}%")
        print(f"Average Latency: {avg_latency}ms")
        
        print("Saving results to database...")
        try:
            requests.post(f"{API_URL}/evals", json={
                "goldenDatasetVersion": "v1.0",
                "accuracy": float(accuracy),
                "avgLatencyMs": int(avg_latency)
            })
            print("Results saved successfully.")
        except Exception as e:
            print(f"Failed to save results: {e}")

if __name__ == "__main__":
    harness = EvalHarness()
    asyncio.run(harness.run_all())
