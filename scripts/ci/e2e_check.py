"""End-to-end pipeline check for ci.yml's pipeline-e2e job.

Triggers eval case1 (null guard removed from UserService.processPayment, branch
case/npe of the fixture repo) through the real stack — gateway, orchestrator,
all agents, guardrails, the test runner's Maven run in Docker — with the LLM
replaced by ai_core/tests/mock_llm_server.py, then asserts:

  * the job reaches a final state within the timeout (no silent stall);
  * the timeline shows INVESTIGATING -> PLANNING -> FIXING -> TESTING in order;
  * the stored patch is the null guard the mock asked for;
  * it did not FAIL (guardrails or the real tests rejecting the patch). PR CI has
    no GitHub App credentials, so ESCALATED with "PR creation failed" is the
    expected end — the nightly eval covers opening the PR.

This is the check that would have caught the lz4 bug on the PR that caused it,
in minutes and for free, instead of in the nightly eval.

On failure it prints the job and its whole timeline, so the CI log says where
the pipeline stopped.
"""
import argparse
import json
import os
import sys
import time

import requests

TERMINAL = {"PR_OPENED", "ESCALATED", "FAILED"}
EXPECTED_ORDER = ["INVESTIGATING", "PLANNING", "FIXING", "TESTING"]
GUARD = "if (user == null)"


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repo-url", required=True)
    parser.add_argument("--branch", default="case/npe")
    parser.add_argument("--api", default=os.environ.get("EVAL_API_URL", "http://localhost:8080/api/v1"))
    parser.add_argument("--timeout", type=int, default=900)
    args = parser.parse_args()

    headers = {"X-API-Key": os.environ.get("GITORACLE_API_KEY", "")}
    resp = requests.post(f"{args.api}/trigger", headers=headers, timeout=30, json={
        "repoUrl": args.repo_url, "branch": args.branch,
        "issueDescription": "NullPointerException in UserService.processPayment (user.getId()). "
                            "processPayment is sometimes called with a null user.",
    })
    if not resp.ok:
        print(f"::error::/trigger failed: HTTP {resp.status_code} {resp.text[:500]}")
        return 1
    job_id = resp.json()["jobId"]
    print(f"Triggered job {job_id} on {args.repo_url}@{args.branch}")

    job: dict = {}
    deadline = time.time() + args.timeout
    while time.time() < deadline:
        job = requests.get(f"{args.api}/jobs/{job_id}", headers=headers, timeout=10).json()
        if job.get("state") in TERMINAL:
            break
        time.sleep(5)
    events = requests.get(f"{args.api}/jobs/{job_id}/events", headers=headers, timeout=10).json()

    problems = []
    state = job.get("state")
    if state not in TERMINAL:
        problems.append(f"job never finished: still {state} after {args.timeout}s")
    if state == "FAILED":
        problems.append(f"job FAILED: {job.get('stateReason')}")
    if state == "ESCALATED" and not (job.get("stateReason") or "").startswith("PR creation failed"):
        problems.append(f"job escalated before the PR step: {job.get('stateReason')}")
    if GUARD not in (job.get("fixPatch") or ""):
        problems.append("stored fixPatch does not contain the null guard")

    seen = [e["toState"] for e in events if e.get("type") == "STATE_CHANGE" and e.get("toState")]
    positions = [seen.index(s) if s in seen else -1 for s in EXPECTED_ORDER]
    if -1 in positions or positions != sorted(positions):
        problems.append(f"timeline out of order or incomplete: {seen} (expected {EXPECTED_ORDER} in order)")

    print(f"\nFinal state: {state} — {job.get('stateReason')}")
    print("Timeline:")
    for e in events:
        label = e.get("toState") or e.get("type")
        print(f"  {e.get('createdAt')}  {e.get('actor'):<14} {label:<14} {e.get('message') or ''}")

    if problems:
        for p in problems:
            print(f"::error::{p}")
        print("\nJob:", json.dumps({k: job.get(k) for k in ("state", "stateReason", "attempts", "rootCommit")}))
        return 1
    print("\n✅ Pipeline end-to-end check passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
