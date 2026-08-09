from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel
from typing import List, Optional
import os
import logging
import re
import sys
import hashlib
import difflib
import shutil
import git

# Ensure ai_core modules can be imported
sys.path.append(os.path.dirname(os.path.dirname(os.path.dirname(__file__))))
from shared.structured_output import llm_structured
from shared.memory import AgentMemory
from shared.prompt_registry import fetch_prompt_versioned, report_prompt_version

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

app = FastAPI(title="GitOracle Fixer Agent", version="1.0")
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

class FixStrategy(str):
    pass

class PlannerOutput(BaseModel):
    strategy: str
    affected_files: List[str]
    affected_functions: List[str]
    max_lines_to_change: int
    reasoning: str
    confidence: float

class FixerRequest(BaseModel):
    tenant_id: str
    repo_path: str
    bug_description: str
    plan: PlannerOutput
    job_id: str = "debug-job"
    human_instructions: Optional[str] = None  # Set when triggered via dashboard or GitHub comment
    target_repo: Optional[str] = None          # GitHub repo to open PR against (owner/repo)
    repo_url: Optional[str] = None             # Git remote to clone for self-testing (GitHub or file://)
    branch: Optional[str] = None               # Branch to check out; None/empty = repo's default branch

class PatchOutput(BaseModel):
    diff: str                   # unified diff format
    summary: str                # one-line description of change
    files_modified: List[str]   # must match Planner's affected_files
    new_tests: Optional[str] = None # optional: new test cases agent wrote
    confidence: float

class PatchResult(BaseModel):
    patch: Optional[PatchOutput]
    attempts: int
    success: bool
    escalation_report: Optional[str] = None

class SearchReplaceEdit(BaseModel):
    """
    What the LLM actually produces: an exact block to find and its replacement,
    for a single file. Asking an LLM to hand-write a unified diff (precise
    @@ -a,b +c,d @@ line counts, correct line numbers) is unreliable — confirmed
    live, patches consistently failed `git apply` with "corrupt patch" even
    after giving the model line-numbered source and explicit format rules.
    Reproducing a verbatim snippet plus its replacement is a task LLMs handle
    far more reliably; the unified diff is then computed with difflib, which
    can never produce invalid diff syntax.
    """
    file_path: str              # one of plan.affected_files, relative to repo root
    search: str                 # exact existing text to locate (verbatim from source context)
    replace: str                # replacement text
    summary: str
    confidence: float


def build_unified_diff(repo_path: str, file_path: str, search: str, replace: str) -> Optional[str]:
    """Locate `search` verbatim in the file and return a valid unified diff replacing
    it with `replace`, computed via difflib so the diff syntax is always correct.
    Returns None if `search` isn't found (the LLM didn't reproduce the text exactly)
    or if the edit would leave brace-based source unbalanced — the diff itself is
    always syntactically valid, but a `replace` block that opens more braces than
    it closes (or vice versa) produces code that fails to compile (confirmed live:
    "reached end of file while parsing"). A cheap global brace-count check catches
    this before spending a full test-runner round-trip on code that can't compile."""
    full_path = os.path.join(repo_path, file_path)
    try:
        with open(full_path, "r") as f:
            original = f.read()
    except Exception as e:
        logger.warning(f"Could not read {full_path} to build diff: {e}")
        return None

    if search not in original:
        return None

    updated = original.replace(search, replace, 1)
    if updated == original:
        return None

    if file_path.endswith((".java", ".c", ".cpp", ".h", ".cs", ".go", ".js", ".ts", ".jsx", ".tsx")):
        if updated.count("{") != updated.count("}"):
            logger.warning(f"Rejecting edit to {file_path}: unbalanced braces after replace")
            return None

    diff_lines = difflib.unified_diff(
        original.splitlines(keepends=True),
        updated.splitlines(keepends=True),
        fromfile=f"a/{file_path}",
        tofile=f"b/{file_path}",
    )
    diff_text = "".join(diff_lines)
    return diff_text if diff_text else None


# Matches path-like tokens with a file extension, e.g. "backend/signaling/views.py"
# or "views.py". Dashboard "Ask to Fix" jobs always send plan.affected_files=[] (see
# DashboardController.triggerFix) — the fixer never got shown any source, so it was
# asked to reproduce an exact verbatim block from a file it never saw, and rejected
# every attempt. Confirmed live on job 97de3e96: instructions named the file
# explicitly ("backend/signaling/views.py's health_check function") but nothing
# read that file into context. Extract file paths straight out of the instructions.
_FILE_PATH_RE = re.compile(r"[\w][\w\-./]*\.[A-Za-z0-9]{1,10}")


def extract_file_paths_from_text(text: str) -> List[str]:
    if not text:
        return []
    return list(dict.fromkeys(_FILE_PATH_RE.findall(text)))


@app.post("/fix", response_model=PatchResult)
async def execute_fix(request: FixerRequest):
    logger.info(f"Starting ReAct loop for job {request.job_id} in {request.repo_path}")

    memory = AgentMemory()
    seen_patches = set()
    hint = ""

    # The fixer needs its own private, stable checkout to read source from across
    # every retry attempt. request.repo_path is shared with Test Runner (via
    # run_real_test below), which deletes and re-clones that exact path on every
    # self-test call — so by attempt 2, request.repo_path could already be gone,
    # breaking source reads with "No such file or directory" (confirmed live: the
    # investigation and plan were correct, but every retry failed here before an
    # LLM call was even made). Clone into a separate, fixer-owned directory instead.
    local_src_path = request.repo_path
    # git.Repo.clone_from's branch kwarg must be omitted (not passed as None) to
    # get the repo's default branch — passing branch=None explicitly is not the
    # same as not passing it.
    clone_kwargs = {"branch": request.branch} if request.branch else {}
    if request.repo_url:
        fixer_src_path = request.repo_path.rstrip("/") + "-fixer-src"
        try:
            if not os.path.isdir(os.path.join(fixer_src_path, ".git")):
                shutil.rmtree(fixer_src_path, ignore_errors=True)
                git.Repo.clone_from(request.repo_url, fixer_src_path, **clone_kwargs)
            local_src_path = fixer_src_path
        except Exception as e:
            # request.repo_path is only populated when an earlier pipeline stage
            # (e.g. the investigator) already cloned it — jobs triggered via
            # "Ask to Fix" skip that stage, so repo_path can be an empty/nonexistent
            # directory. Confirmed live: a transient clone failure here (DNS blip)
            # silently fell through to reading from that empty directory for all 3
            # attempts, guaranteeing "file not found" on every retry instead of
            # actually retrying the clone. Retry once against repo_path itself.
            logger.warning(f"Could not set up private source checkout into {fixer_src_path}: {e}")
            try:
                if not os.path.isdir(os.path.join(request.repo_path, ".git")):
                    shutil.rmtree(request.repo_path, ignore_errors=True)
                    git.Repo.clone_from(request.repo_url, request.repo_path, **clone_kwargs)
                local_src_path = request.repo_path
            except Exception as e2:
                logger.warning(f"Fallback clone into {request.repo_path} also failed: {e2}")

    # 1. Fetch source code for context
    affected_files = request.plan.affected_files
    if not affected_files:
        candidates = extract_file_paths_from_text(
            (request.human_instructions or "") + " " + request.bug_description
        )
        resolved = []
        for candidate in candidates:
            if os.path.isfile(os.path.join(local_src_path, candidate)):
                resolved.append(candidate)
                continue
            # Instructions may name just a basename (e.g. "views.py") rather than
            # the full relative path — search the checkout for a unique match.
            basename = os.path.basename(candidate)
            matches = []
            for root, _, files in os.walk(local_src_path):
                if ".git" in root.split(os.sep):
                    continue
                if basename in files:
                    matches.append(os.path.relpath(os.path.join(root, basename), local_src_path))
            if len(matches) == 1:
                resolved.append(matches[0])
            elif matches:
                logger.warning(f"Ambiguous file reference '{candidate}': matches {matches}")
        if resolved:
            logger.info(f"No affected_files in plan; resolved from instructions: {resolved}")
            affected_files = resolved
        else:
            logger.warning("No affected_files in plan and none could be resolved from instructions")

    source_context = ""
    for file_path in affected_files:
        full_path = os.path.join(local_src_path, file_path)
        try:
            with open(full_path, "r") as f:
                source_context += f"\n--- {file_path} ---\n{f.read()}\n"
        except Exception as e:
            logger.warning(f"Could not read {full_path}: {e}")
            source_context += f"\n--- {file_path} ---\n[File not found or unreadable]\n"

    # ReAct Loop (Up to 3 attempts to save time during testing, normally 5)
    for attempt in range(3):
        logger.info(f"Fixer Attempt {attempt + 1}")
        
        # 2. Retrieve Episodic Memory
        try:
            memories = await memory.recall(request.tenant_id, request.repo_path, request.bug_description, "episodic", top_k=2)
            past_fixes = "\n".join([f"- {m['content']} (Score: {m['metadata'].get('quality_score', 1.0)})" for m in memories])
        except Exception as e:
            logger.warning(f"Could not retrieve episodic memory: {e}")
            past_fixes = "None"
            
        # 3. Formulate Prompt — inject human instructions as top-priority constraint if present
        human_block = ""
        if request.human_instructions:
            human_block = f"""
⚠️ CRITICAL INSTRUCTION FROM USER (HIGHEST PRIORITY — MUST BE FOLLOWED EXACTLY):
{request.human_instructions}
Your patch MUST implement this instruction. All other guidelines are secondary.
"""
        base_prompt, prompt_version = await fetch_prompt_versioned(
            "fixer",
            "You are the GitOracle Fixer Agent. A Planner Agent has given you a strict blueprint to fix a bug. "
            "You MUST write a patch (unified diff) that fixes the bug according to the plan."
        )
        # Attribute this job to the prompt revision that produced its patch, so
        # per-version accuracy can be measured rather than asserted. Idempotent
        # server-side, so repeating it across ReAct attempts is harmless.
        await report_prompt_version(request.job_id, "fixer", prompt_version)
        prompt_text = f"""
{base_prompt}

Bug Description: {request.bug_description}
{human_block}
Architect's Blueprint:
Strategy: {request.plan.strategy}
Max Lines to Change: {request.plan.max_lines_to_change}
Reasoning: {request.plan.reasoning}

Past Successful Fixes for Similar Bugs:
{past_fixes}

Source Code Context:
{source_context[:4000]}  # Trimmed for safety

{hint}

Generate a search/replace edit for exactly one file:
- file_path: the file you're editing (must be one of the affected files shown above).
- search: an EXACT, VERBATIM copy of the block of existing lines you're replacing, copied character-for-character from the source context above (same indentation, same whitespace). Include enough surrounding lines to uniquely identify the location — a single line is fine if it's already unique in the file.
- replace: the full replacement text for that exact block (can be longer or shorter than search).
Do not write a diff yourself — the unified diff is computed automatically from search/replace.
"""
        messages = [
            {"role": "system", "content": "You are a master programmer AI. You make precise, minimal code edits."},
            {"role": "user", "content": prompt_text}
        ]

        # 4. Call LLM (Layer 3b)
        try:
            edit = await llm_structured(
                messages=messages,
                output_schema=SearchReplaceEdit,
                max_tokens=4096,
                temperature=0.2 + (attempt * 0.2), # Increase temp on retries for creativity
                job_id=request.job_id,
                agent_name="fixer_agent"
            )
        except Exception as e:
            logger.error(f"LLM call failed: {e}")
            continue

        diff_text = build_unified_diff(local_src_path, edit.file_path, edit.search, edit.replace)
        if diff_text is None:
            logger.warning(f"Rejected edit to {edit.file_path} — asking the model to retry")
            hint = (
                f"WARNING: Your previous edit to {edit.file_path} was rejected — either the 'search' text didn't "
                "match exactly (copy it verbatim, including whitespace/indentation, from the source context above), "
                "or 'replace' left mismatched braces. Ensure every '{' in your replace text has a matching '}'."
            )
            continue

        patch = PatchOutput(
            diff=diff_text,
            summary=edit.summary,
            files_modified=[edit.file_path],
            confidence=edit.confidence,
        )

        # 5. Self-Healing Loop Detection
        patch_hash = hashlib.sha256(patch.diff.encode('utf-8')).hexdigest()
        if patch_hash in seen_patches:
            logger.warning("Loop detected! Agent generated the exact same patch.")
            hint = "WARNING: Your previous patch failed the tests. You MUST try a fundamentally different approach. Do not output the same diff."
            continue
        
        seen_patches.add(patch_hash)
        
        import httpx
        
        async def run_real_test(job_id: str, repo_path: str, repo_url: str, patch_diff: str, branch: str = "") -> bool:
            try:
                async with httpx.AsyncClient() as client:
                    response = await client.post(
                        "http://localhost:8084/test",
                        json={
                            "jobId": job_id,
                            "repoUrl": repo_url,
                            "repoPath": repo_path,
                            "patchDiff": patch_diff,
                            "branch": branch,
                            "framework": "UNKNOWN"  # let Test Runner auto-detect (pom.xml/package.json/etc.)
                        },
                        timeout=130.0
                    )
                    response.raise_for_status()
                    data = response.json()
                    logger.info(f"Real Test Runner result: {data.get('allPassed', data.get('success'))}")
                    passed = data.get('allPassed', data.get('passed', data.get('success', False)))
                    if not passed:
                        logger.info(f"Test Logs: {data.get('logs', '')}")
                    return passed
            except Exception as e:
                logger.error(f"Error calling real test runner: {e}")
                return False

        # 6. Run tests (Layer 6 real execution)
        if request.job_id.startswith("mock-test-"):
            logger.info("Bypassing tests for mock-test- job.")
            tests_passed = True
        else:
            tests_passed = await run_real_test(
                request.job_id, request.repo_path, request.repo_url or "", patch.diff,
                request.branch or ""
            )
        
        if tests_passed:
            # 7. Store successful fix in episodic memory
            try:
                episode_text = f"Fixed bug '{request.bug_description}' using strategy {request.plan.strategy}. Key change: {patch.summary}."
                await memory.remember(request.tenant_id, request.repo_path, "episodic", episode_text, metadata={"quality_score": 1.0})
                logger.info("Saved success to Episodic Memory.")
            except Exception as e:
                logger.warning(f"Failed to store memory: {e}")
                
            return PatchResult(patch=patch, attempts=attempt + 1, success=True)
        else:
            hint = f"WARNING: Your patch '{patch.summary}' failed the tests. Please try again and fix any syntax or logic errors."
            
    # Escalation
    return PatchResult(patch=None, attempts=3, success=False, escalation_report="Fixer Agent exhausted all attempts and could not fix the bug.")

import asyncio
from shared.kafka_consumer import KafkaEventConsumer
from shared.kafka_producer import KafkaEventProducer

async def handle_fix_job(payload: dict):
    logger.info(f"Received Kafka event for fixer: {payload}")
    
    job_id = payload.get("job_id", "unknown")
    repo_path = payload.get("repo_path", "")
    repo_url = payload.get("repo_url", "")
    plan_dict = payload.get("plan", {})
    human_instructions = payload.get("human_instructions")
    target_repo = payload.get("target_repo", "")
    branch = payload.get("branch") or None

    plan = PlannerOutput(**plan_dict)

    bug_desc = payload.get("bug_description", "Fixing error based on plan")
    if human_instructions:
        bug_desc = human_instructions  # user's instructions become the primary description

    request = FixerRequest(
        tenant_id="00000000-0000-0000-0000-000000000000",
        repo_path=repo_path,
        bug_description=bug_desc,
        plan=plan,
        job_id=job_id,
        human_instructions=human_instructions,
        target_repo=target_repo if target_repo else None,
        repo_url=repo_url if repo_url else None,
        branch=branch
    )
    
    try:
        result = await execute_fix(request)
        producer = KafkaEventProducer()
        if result.success and result.patch:
            fix_payload = {
                "jobId": job_id,
                "patch": result.patch.diff,
                "targetRepo": target_repo or "",
                "humanInstructions": human_instructions or "",
                "isRegeneration": bool(human_instructions),
                "filesModified": ",".join(result.patch.files_modified),
                "branch": branch or "",
            }
            await producer.publish("fix-generated", fix_payload)
            logger.info(f"Published fix-generated for job {job_id} (human_directed={bool(human_instructions)})")
        else:
            logger.warning(f"Fixer failed to produce a valid patch for job {job_id}")
            await producer.publish("job-escalated", {
                "jobId": job_id,
                "reason": result.escalation_report or "Fixer Agent exhausted all attempts and could not fix the bug.",
                # Was hardcoded 0.0 regardless of the actual plan/investigation
                # confidence — confirmed live: every row in the Escalation Queue
                # showed 0.0 even when the underlying investigation had a real
                # confidence_score of 0.7-1.0, making the "Avg Confidence" stat
                # on that page permanently meaningless. Use the planner's own
                # confidence in what it asked the fixer to do.
                "confidenceScore": request.plan.confidence
            })
            logger.info(f"Published job-escalated for job {job_id}")
    except Exception as e:
        logger.error(f"Fix execution failed for job {job_id}: {e}")

@app.on_event("startup")
async def startup_event():
    consumer = KafkaEventConsumer(topic="job.events.fix", group_id="fixer-agent-group")
    asyncio.create_task(consumer.consume(handle_fix_job))

if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=9002)
