import argparse
import json
import os
import re
import sys
import time
import requests
from pathlib import Path
from pydantic import BaseModel
import asyncio
from dotenv import load_dotenv

# Load env variables from project root
load_dotenv(Path(__file__).parent.parent / ".env")

# Through the gateway, not the orchestrator's own port: the orchestrator rejects
# every request without X-Internal-Token, and TenantContext needs the tenant the
# gateway resolves from X-API-Key. Hitting :8083 directly 401'd on the first call.
API_URL = os.environ.get("EVAL_API_URL", "http://localhost:8080/api/v1").rstrip("/")
API_HEADERS = {"X-API-Key": os.environ.get("GITORACLE_API_KEY", "")}

# RepoRefValidator only accepts https://github.com/... — a file:// URL is
# rejected with a 400 at /trigger. The golden repo has to live on GitHub;
# setup_test_repo.py --push publishes it there.
EVAL_REPO_URL = os.environ.get("EVAL_REPO_URL", "")

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
    status: str
    cause_correct: bool
    patch_score: float
    latency_ms: int
    graded: bool

class EvalHarness:
    def __init__(self, repo_url: str, golden_dir: str = "golden"):
        self.golden_dir = Path(__file__).parent / golden_dir
        self.repo_url = repo_url

    def grade_patch(self, expected: str, actual: str) -> int | None:
        """Judge score 0-10, or None if the judge call itself failed.

        A failed grading call used to return 0, indistinguishable from "the agent
        wrote a bad patch" — a misconfigured LLM key scored every run 0% with no
        sign anything was wrong. None is counted separately in the report.
        """
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
                    # Was 10. Reasoning models (e.g. openai/gpt-oss-120b on Groq)
                    # spend tokens thinking before they answer, so they hit the
                    # limit with an EMPTY reply (finish_reason="length"), which was
                    # scored 0. Confirmed: the same correct fix scored 0 at 10 tokens
                    # and 10/10 at 1024 (131 tokens used). Every patch in the second
                    # CI eval run scored 0 this way, including one whose PR opened
                    # after passing the real tests.
                    "max_tokens": 1024
                },
                timeout=60
            )
            resp.raise_for_status()
            choice = resp.json()["choices"][0]
            content = (choice["message"].get("content") or "").strip()
            score = self._parse_score(content)
            if score is None:
                # No usable answer is a grading failure, not a score of 0.
                print(f"Judge gave no score (finish_reason={choice.get('finish_reason')!r}, reply={content[:100]!r})")
            return score
        except Exception as e:
            print(f"Error grading patch: {e}")
            return None

    @staticmethod
    def _parse_score(content: str) -> int | None:
        """First integer 0-10 in the judge's reply.

        This used to join every digit in the reply, so "7/10" became 710 (and
        clamped to 10), and "Score: 3 out of 10" became 310 → 10.
        """
        match = re.search(r"\b(10|[0-9])\b", content)
        return int(match.group(1)) if match else None

    async def run_case(self, case_id: str) -> EvalResult:
        print(f"\n[{case_id}] Starting eval run...")
        case_path = self.golden_dir / case_id
        
        with open(case_path / "expected_root_commit.txt") as f:
            expected_root_commit = f.read().strip()
            
        with open(case_path / "expected_fix.patch") as f:
            expected_fix = f.read().strip()
            
        with open(case_path / "bug_description.txt") as f:
            issue_description = f.read().strip()

        # Each case lives on its own branch (see setup_test_repo.py), so one
        # case's failing test can't fail another case's fix.
        branch_file = case_path / "branch.txt"
        branch = branch_file.read_text().strip() if branch_file.exists() else ""

        start_time = time.time()
        
        # Trigger the pipeline
        print(f"[{case_id}] Triggering GitOracle pipeline...")
        try:
            resp = requests.post(f"{API_URL}/trigger", headers=API_HEADERS, timeout=30, json={
                "repoUrl": self.repo_url,
                "issueDescription": issue_description,
                "branch": branch,
            })
            error = None if resp.ok else f"HTTP {resp.status_code} {resp.text[:300]}"
        except requests.RequestException as e:
            error = str(e)
        if error:
            print(f"[{case_id}] Trigger failed: {error}")
            return EvalResult(case_id=case_id, status="TRIGGER_FAILED", cause_correct=False,
                              patch_score=0.0, latency_ms=0, graded=False)
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
                job_resp = requests.get(f"{API_URL}/jobs/{job_id}", headers=API_HEADERS, timeout=10)
                job_resp.raise_for_status()
                data = job_resp.json()
                status = data.get("state", "UNKNOWN")
                print(f"[{case_id}] Status: {status}   ", end="\r")
            except Exception:
                pass

        latency_ms = int((time.time() - start_time) * 1000)
        print(f"\n[{case_id}] Job finished with status {status} in {latency_ms}ms")

        # Grade whatever the pipeline actually produced, regardless of terminal
        # state. Whether the final PR step succeeds depends on the GitHub App being
        # configured for the fixture repo (eval.yml does this; a bare local run
        # usually doesn't), and without it a job with a valid, test-passing patch
        # lands on ESCALATED. Gating on PR_OPENED would throw that patch away and
        # score it 0. Opening a PR is not what this harness measures; the quality
        # of the patch is.
        agent_root_commit = data.get("rootCommit") or ""
        agent_patch = data.get("fixPatch") or ""
            
        cause_correct = (agent_root_commit == expected_root_commit)
        print(f"[{case_id}] Root Cause Correct: {cause_correct} (Expected: {expected_root_commit[:8]}, Got: {agent_root_commit[:8]})")
        
        score = self.grade_patch(expected_fix, agent_patch)
        print(f"[{case_id}] LLM Judge Score: {score}/10")
        
        return EvalResult(
            case_id=case_id,
            status=status,
            cause_correct=cause_correct,
            patch_score=(score or 0) / 10.0,
            latency_ms=latency_ms,
            graded=score is not None,
        )

    async def run_all(self) -> dict | None:
        print("Starting GitOracle Automated Evaluation...")
        cases = sorted([d.name for d in self.golden_dir.iterdir() if d.is_dir()])
        
        if not cases:
            print("No test cases found in golden dataset.")
            return None
            
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
        # cause_accuracy is reported but NOT folded into the stored number. /trigger
        # defaults to investigateFirst=true, so the Investigator does run and its
        # top-ranked cause overwrites rootCommit. But the orchestrator first sets
        # rootCommit to the clone's HEAD as a placeholder, and case2's bug commit
        # IS HEAD, so case2 scores "correct" even if the Investigator never answers.
        # Until the dataset has cases whose cause is not HEAD, the number is too
        # easy to trust. (The old combined metric ANDed the two together, which
        # also capped stored accuracy at whatever this flawed signal allowed.)
        patch_accuracy = sum(1 for r in results if r.patch_score >= 0.7) / len(results)
        cause_accuracy = sum(1 for r in results if r.cause_correct) / len(results)

        print(f"Total Cases: {len(results)}")
        print(f"Average Patch Score: {avg_score * 100:.1f}%")
        print(f"Patch Accuracy (score >= 7/10): {patch_accuracy * 100:.1f}%   <- stored as 'accuracy'")
        print(f"Cause Accuracy (correct root commit): {cause_accuracy * 100:.1f}%"
              f"   (not stored: case2's cause is HEAD, which the placeholder matches)")
        print(f"Average Latency: {avg_latency}ms")
        accuracy = patch_accuracy
        
        print("Saving results to database...")
        try:
            save = requests.post(f"{API_URL}/evals", headers=API_HEADERS, timeout=10, json={
                "goldenDatasetVersion": "v1.0",
                "accuracy": float(accuracy),
                "casesTotal": len(results),
                "avgLatencyMs": int(avg_latency)
            })
            save.raise_for_status()
            print("Results saved successfully.")
        except Exception as e:
            print(f"Failed to save results: {e}")

        # check_regression.py reads "accuracy" from this.
        return {
            "accuracy": accuracy,
            "patch_accuracy": patch_accuracy,
            "cause_accuracy": cause_accuracy,
            "avg_patch_score": avg_score,
            "avg_latency_ms": avg_latency,
            "cases_total": len(results),
            "cases_incomplete": sum(1 for r in results if r.status not in TERMINAL_STATES),
            "cases_ungraded": sum(1 for r in results if not r.graded),
            "cases": [r.model_dump() for r in results],
        }

def main() -> int:
    parser = argparse.ArgumentParser(description="Run the golden dataset through the live pipeline")
    parser.add_argument("--output", help="Write the JSON report here (read by check_regression.py)")
    parser.add_argument("--repo-url", default=EVAL_REPO_URL,
                        help="https://github.com/... URL of the golden repo (default: $EVAL_REPO_URL)")
    args = parser.parse_args()

    if not args.repo_url:
        print("No golden repo URL: pass --repo-url or set EVAL_REPO_URL (see setup_test_repo.py --push).")
        return 2

    report = asyncio.run(EvalHarness(args.repo_url).run_all())
    if report is None:
        return 1
    if args.output:
        Path(args.output).write_text(json.dumps(report, indent=2))
        print(f"Report written to {args.output}")

    # A case that never finished, or a judge that never answered, is a broken
    # run, not a low score — fail loudly instead of letting it pass as a number.
    if report["cases_incomplete"] or report["cases_ungraded"]:
        print(f"FAILURE: {report['cases_incomplete']} case(s) did not finish, "
              f"{report['cases_ungraded']} could not be graded")
        return 1
    return 0

if __name__ == "__main__":
    sys.exit(main())
