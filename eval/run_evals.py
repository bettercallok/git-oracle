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

# .env's LLM_BASE_URL is a full chat-completions endpoint (it is consumed that way
# by ai_core/shared/structured_output.py), e.g.
#   https://api.groq.com/openai/v1/chat/completions
# This module used to append "/chat/completions" to it, producing a doubled path
# that 404s on every grading call — the LLM judge then silently scored 0 for every
# case. Accept either form and normalise.
LLM_CHAT_URL = os.environ.get("LLM_BASE_URL", "http://127.0.0.1:8000/v1")
if not LLM_CHAT_URL.rstrip("/").endswith("/chat/completions"):
    LLM_CHAT_URL = LLM_CHAT_URL.rstrip("/") + "/chat/completions"

# The rest of the stack authenticates with OPENAI_API_KEY (Groq speaks the
# OpenAI-compatible API); LLM_API_KEY was read here but is not defined in .env,
# so grading requests went out unauthenticated.
LLM_API_KEY = os.environ.get("LLM_API_KEY") or os.environ.get("OPENAI_API_KEY", "dummy-key")

# Grading must use whatever model the deployment is actually configured for —
# "qwen2.5-coder" was hardcoded and does not exist on the configured backend.
LLM_MODEL = os.environ.get("LLM_MODEL_NAME", "llama-3.3-70b-versatile")

# The orchestrator never sets a "SUCCESS" state; grep the Java sources and the
# only terminal states written are PR_OPENED (success), ESCALATED and FAILED.
# Polling for "SUCCESS" meant a successful run never satisfied the loop condition
# and the harness hung forever on its first case.
TERMINAL_STATES = {"PR_OPENED", "ESCALATED", "FAILED"}
SUCCESS_STATES = {"PR_OPENED"}

# A full pipeline pass (investigate → plan → fix → test → PR) plus LLM latency.
POLL_TIMEOUT_SECONDS = int(os.environ.get("EVAL_POLL_TIMEOUT", "600"))

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
                LLM_CHAT_URL,
                headers={"Authorization": f"Bearer {LLM_API_KEY}"},
                json={
                    "model": LLM_MODEL,
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
        data = {}

        print(f"[{case_id}] Waiting for job {job_id} to complete...")
        # Bounded wait: the loop previously had no timeout at all, so any job that
        # never reached a terminal state hung the whole harness indefinitely.
        deadline = time.time() + POLL_TIMEOUT_SECONDS
        while status not in TERMINAL_STATES:
            if time.time() > deadline:
                print(f"\n[{case_id}] TIMEOUT after {POLL_TIMEOUT_SECONDS}s (last status: {status})")
                status = "TIMEOUT"
                break
            time.sleep(2)
            try:
                job_resp = requests.get(f"{API_URL}/jobs/{job_id}")
                job_resp.raise_for_status()
                data = job_resp.json()
                status = data.get("state", "UNKNOWN")
                print(f"[{case_id}] Status: {status}   ", end="\r")
            except Exception:
                pass

        latency_ms = int((time.time() - start_time) * 1000)
        print(f"\n[{case_id}] Job finished with status {status} in {latency_ms}ms")

        # Grade whatever the pipeline actually produced, regardless of terminal
        # state. The golden repo is a local file:// checkout, so the final PR step
        # ALWAYS fails ("Repository name must be in format owner/repo") and the job
        # ALWAYS lands on ESCALATED — even when the fix was generated and the tests
        # passed. Gating on PR_OPENED therefore threw away every valid patch and
        # scored a perfect run 0. Opening a GitHub PR is not what this harness
        # measures; the quality of the patch is.
        agent_root_commit = data.get("rootCommit") or ""
        agent_patch = data.get("fixPatch") or ""
            
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

        # Two distinct metrics, reported separately rather than conflated.
        #
        # patch_accuracy is what gets stored and shown on the dashboard: did the
        # agent produce a fix the LLM judge grades >= 7/10 against the golden fix.
        #
        # cause_accuracy is reported but NOT folded into the stored number, because
        # this harness drives the pipeline through POST /trigger, which publishes
        # straight to job.events.fix and never runs the Investigator — so rootCommit
        # is never populated and cause_accuracy is structurally 0. The old combined
        # metric ANDed the two together, which meant the stored accuracy could never
        # exceed 0 no matter how good the patches were. Exercising root-cause
        # attribution requires driving the webhook entry point instead.
        patch_accuracy = sum(1 for r in results if r.patch_score >= 0.7) / len(results)
        cause_accuracy = sum(1 for r in results if r.cause_correct) / len(results)

        print(f"Total Cases: {len(results)}")
        print(f"Average Patch Score: {avg_score * 100:.1f}%")
        print(f"Patch Accuracy (score >= 7/10): {patch_accuracy * 100:.1f}%   <- stored as 'accuracy'")
        print(f"Cause Accuracy (correct root commit): {cause_accuracy * 100:.1f}%"
              f"   (always 0 via /trigger — Investigator is bypassed)")
        print(f"Average Latency: {avg_latency}ms")
        accuracy = patch_accuracy
        
        print("Saving results to database...")
        try:
            requests.post(f"{API_URL}/evals", json={
                "goldenDatasetVersion": "v1.0",
                "accuracy": float(accuracy),
                "casesTotal": len(results),
                "avgLatencyMs": int(avg_latency)
            })
            print("Results saved successfully.")
        except Exception as e:
            print(f"Failed to save results: {e}")

if __name__ == "__main__":
    harness = EvalHarness()
    asyncio.run(harness.run_all())
