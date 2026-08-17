# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

GitOracle is an event-driven, multi-agent autonomous coding system: point it at a bug (github webhook, plain-english description, or sentry-style report) and it investigates the root cause across git history, plans a scoped fix, writes a patch, runs it against the real test suite, and opens a pull request. 6 Java Spring Boot 3.2 / Java 21 microservices and 7 Python FastAPI agents communicate almost entirely through Kafka, with synchronous HTTP calls only where a hard dependency makes sense (guardrails validation, test execution, PR creation). No service holds pipeline state in memory — a job's state lives in Postgres (`AgentJob`), so any service can restart without losing its place.

## Commands

### Local dev (the normal workflow)
```bash
cp .env.example .env               # fill in LLM_BASE_URL/LLM_MODEL_NAME/OPENAI_API_KEY, GITORACLE_API_KEY, github app creds
make infra-up                      # kafka, postgres+pgvector, neo4j, qdrant, redis, langfuse, prometheus, grafana, kafka UI
./start_local.sh                   # builds all 6 java jars (~10 min first time), starts all 6 java services + 7 python agents + dashboard as local processes
./stop_local.sh                    # stops app processes only (NOT infra)
make infra-down                    # stops infra containers, keeps volumes
```
**Never kill service ports with `lsof -ti :<port> | xargs kill`.** Prometheus (in a container) holds a live connection to every scraped host port (`host.docker.internal:8081-8085,9001-9006`), so `com.docker.backend` shows up in `lsof -i :<port>` output right next to the real service — that shortcut has killed Docker Desktop's backend twice. Always use `./stop_local.sh`, which filters by command name (`java|python|node|npm|uvicorn|gradlew`) with an explicit Docker denylist.

Java services are launched with plain `java -jar`, never `./gradlew bootRun` — `bootRun` keeps a second, uncapped Gradle build-supervisor JVM resident for the service's entire runtime (6 services → 12 resident JVMs instead of 6). `start_local.sh` builds jars once via a single short-lived `./gradlew --no-daemon :<module>:bootJar -x test` invocation per service, then runs the jars directly.

After a restart, confirm a Java service is actually ready by grepping its log for `"Started <X>Application"`, not by curling its port — a dying old process can leave a port that still answers healthchecks for a moment during the handoff.

Alternative: containerized java services instead of local processes: `docker compose -f docker-compose.services.yml up -d --build`.

### Build / test — Java (`java-backend/`)
```bash
cd java-backend
./gradlew test --no-daemon                          # all modules
./gradlew :orchestrator:test --no-daemon             # one module
./gradlew :orchestrator:test --no-daemon --tests "ai.gitoracle.orchestrator.OrchestratorServiceTest"
./gradlew :orchestrator:test --no-daemon --tests "*.OrchestratorServiceTest.handleTestsPassed_idempotent*"
```
`gradle.properties` disables the gradle daemon and caps `jvmargs` to `-Xmx768m` — this is deliberate (repeated invocations otherwise accumulate idle daemons and exhaust host RAM), don't "fix" it by re-enabling the daemon.

### Build / test — Python (`ai_core/`)
```bash
cd ai_core && source .venv/bin/activate
pip install -r requirements.txt
ruff check ai_core --select E9,F                    # lint (CI gate)
mypy ai_core/agents --ignore-missing-imports         # typecheck (CI: non-blocking, `|| true`)
python ai_core/tests/mock_llm_server.py &            # CI starts this first; several tests hit it
pytest ai_core/tests/                                # full suite
pytest ai_core/tests/test_build_unified_diff.py -v   # single file
pytest ai_core/tests/test_build_unified_diff.py::test_name -v
```
`ai_core/test_*.py` at the package root (`test_fixer.py`, `test_investigator.py`, `test_planner.py`, `test_memory.py`, etc.) are standalone manual smoke scripts run directly with `python`, not part of the pytest suite — the real regression suite is `ai_core/tests/` only.

### Dashboard (`dashboard/`)
```bash
cd dashboard
npm run dev      # vite dev server, :5173
npm run build    # tsc -b && vite build
npm run lint      # oxlint
```

### Eval harness (root-cause + patch-quality regression measurement)
```bash
python eval/setup_test_repo.py     # builds a throwaway local repo (/tmp/gitoracle-eval-repo) with 2 seeded bugs
python eval/run_evals.py           # drives the golden dataset through the live pipeline, needs services running
python eval/check_regression.py --report eval_report.json --min-score 0.75
```
Note: `run_evals.py` drives cases through `POST /trigger` (the direct-fix shortcut), which never exercises the Investigator — so `rootCommit` is structurally never populated and `cause_accuracy` is reported but not folded into the stored `accuracy` metric. Exercising root-cause attribution requires driving the real webhook entry point instead (see "two entry points" below).

## Architecture

### Two parallel Kafka-naming conventions — don't assume one file is the source of truth
`java-backend/git-oracle-core/.../kafka/KafkaTopics.java` centralizes constants for the **Java↔Java** events: `ERROR_INGESTED`, `FORENSICS_COMPLETE`, `FIX_GENERATED`, `TESTS_PASSED`, `TESTS_FAILED`, `PR_OPENED`, `JOB_ESCALATED`, `GITHUB_PR_EVENTS`, `AUDIT_EVENT`, etc. — dash-named topics (`error-ingested`, `fix-generated`, ...), used via `KafkaTopics.X` references.

The **Java→Python agent dispatch hops** (`job.events.investigate`, `job.events.plan`, `job.events.fix`, `job.events.review.received`) are dot-named and exist only as raw string literals scattered across `OrchestratorService.java`, `DashboardController.java`, and `WebhookController.java` — there is no shared constant for them. When tracing or changing one of these topics, grep for the literal string across all three files; grepping `KafkaTopics.java` alone will miss them.

### Two entry points into the pipeline, both real
1. **`error-ingested` → investigator → planner → fixer** (the "full pipeline"). Triggered by a github webhook, a sentry-style report, or the dashboard's "ask to fix" with the investigate toggle on. `OrchestratorService.handleErrorIngested` clones the repo (at a specific branch if requested), captures HEAD, and dispatches to the investigator — whose top-ranked cause becomes the job's recorded root commit, not just `git rev-parse HEAD`.
2. **`job.events.fix` directly** (the "direct-fix" shortcut, `POST /api/v1/trigger`). Skips investigation with a synthesized plan — ~5x cheaper/faster, correct when a human already knows the fix, but no root-cause data and no file context beyond what's parsed from the instruction text. This is also what the eval harness drives (see above) — a job created this way will never have `rootCommit` populated.

Every per-request override a job can carry — `human_instructions`, `target_repo`, `branch` — is threaded explicitly through every Kafka hop end-to-end rather than looked up from the DB, since these are per-request overrides, not part of a job's durable identity. If you add a new override, thread it the same way (event payload field → every downstream consumer reads it with a default) rather than persisting it on `AgentJob`.

### Python agents are dual-mode: FastAPI server + Kafka consumer in one process
Each of the 7 agents starts a Kafka consumer thread in its `@app.on_event("startup")` hook (topic name hardcoded per-agent, e.g. fixer listens on `job.events.fix`) *and* exposes its own synchronous HTTP endpoint (`/fix`, `/plan`, etc.) that the Kafka handler calls into internally, and that other services also call directly (e.g. the fixer's own self-test call to test-runner, or the orchestrator's synchronous guardrails/test-runner/github-bot calls). Don't assume an agent is reachable only via Kafka or only via HTTP — check both call sites before changing a signature.

### Service map
| service | port | role |
|---|---|---|
| api-gateway (Java) | 8080 | single entry point; fail-closed `X-API-Key` auth + centralized CORS (`TenantContextFilter`), routes `/webhook/**`→error-ingestor, `/api/v1/risk`→git-forensics, `/api/v1/**`→orchestrator |
| error-ingestor (Java) | 8081 | github webhooks + sentry-style reports, semantic dedup, kicks off jobs |
| git-forensics (Java) | 8082 | neo4j-backed risk heatmap queries |
| orchestrator (Java) | 8083 | pipeline brain — owns `AgentJob` state, all Kafka consume/produce, synchronous calls to guardrails/test-runner/github-bot |
| test-runner (Java) | 8084 | clones repo (branch-aware), applies patch, auto-detects + runs real test framework in a sandbox; no tests found = safe-pass |
| github-bot (Java) | 8085 | github app auth (JWT), clones/branches/commits/pushes, opens the actual PR |
| investigator (Python) | 9001 | ranks commits by causal-effect score |
| fixer (Python) | 9002 | ReAct loop, ≤3 attempts, search/replace → `difflib` unified diff, self-tests before publishing |
| reviewer (Python) | 9003 | evaluates human PR comments, decides if a new fix cycle is warranted |
| commit_analyst (Python) | 9004 | natural-language Q&A about a commit for the commit explorer |
| prompt_registry (Python) | 9005 | versioned system prompts from postgres, redis-cached, hot-reload on activation |
| guardrails (Python) | 9006 | validates a patch's touched files ⊆ plan's authorized file list — empty allow-list means deny-all |
| planner (Python) | 9007 | scopes a fix strategy + affected files/functions + max-lines-to-change |

`git-oracle-core` is the shared Java library (JPA entities, `KafkaTopics` constants, event DTOs) — the intended single source of truth for cross-service contracts (with the dot-topic caveat above).

### Storage
Postgres+pgvector (job state, escalations, PR outcomes, eval runs, prompt versions, episodic/semantic agent memory) · Neo4j (repo dependency/risk graph) · Qdrant (RAG context vectors) · Redis (gateway rate limiting, prompt-registry cache) · Langfuse/Prometheus/Grafana/Kafka UI (observability).

### Memory constraints are load-bearing, not incidental
The full stack targets 8GB RAM minimum: `java-backend/gradle.properties` (`org.gradle.daemon=false`, capped `jvmargs`), `start_local.sh`'s per-service `-Xmx`/`-XX:MaxMetaspaceSize` JVM args, and a `mem_limit` on every container in `docker-compose.infra.yml` all exist specifically to prevent Docker Desktop from being OOM-killed on smaller machines — this has happened in practice. Don't remove these caps to "simplify" a service's startup.

## Project structure
- `java-backend/` — 6 Java microservices (each its own gradle subproject) + `git-oracle-core` shared library.
- `ai_core/` — 7 Python FastAPI agents (`agents/`), shared LLM/kafka/memory/prompt-registry helpers (`shared/`), regression suite (`tests/`).
- `dashboard/` — React + TypeScript + Vite web UI (`dashboard/src/api/client.ts` is the single axios client every page uses; base URL via `VITE_API_URL`).
- `cli/` — the `gitOracle` click-based terminal client.
- `eval/` — golden-dataset generator (`setup_test_repo.py`) and harness (`run_evals.py`, `check_regression.py`).
- `infrastructure/` — prometheus/grafana config.
- `llm-server/` — deprecated; inference is now routed to any openai-compatible endpoint via `LLM_BASE_URL`, not a local `llama.cpp` server.
