# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

GitOracle is an event-driven, multi-agent autonomous coding system: point it at a bug (github webhook, plain-english description, or sentry-style report) and it investigates the root cause across git history, plans a scoped fix, writes a patch, runs it against the real test suite, and opens a pull request. 6 Java Spring Boot 3.2 / Java 21 microservices and 7 Python FastAPI agents communicate almost entirely through Kafka, with synchronous HTTP calls only where a hard dependency makes sense (guardrails validation, test execution, PR creation). No service holds pipeline state in memory — a job's state lives in Postgres (`AgentJob`), so any service can restart without losing its place.

## Commands

### Local dev (the normal workflow)
```bash
cp .env.example .env               # fill in LLM_BASE_URL/LLM_MODEL_NAME/OPENAI_API_KEY, github app creds (start_local.sh generates GITORACLE_API_KEY and GITORACLE_INTERNAL_TOKEN)
make infra-up                      # kafka, postgres+pgvector, neo4j, qdrant, redis, langfuse, prometheus, grafana, kafka UI
./start_local.sh                   # builds all 6 java jars (~10 min first time), starts all 6 java services + 7 python agents + dashboard as local processes
./stop_local.sh                    # stops app processes only (NOT infra)
make infra-down                    # stops infra containers, keeps volumes
```
**Never kill service ports with `lsof -ti :<port> | xargs kill`.** Prometheus (in a container) holds a live connection to every scraped host port (`host.docker.internal:8080-8085,9001-9007`), so `com.docker.backend` shows up in `lsof -i :<port>` output right next to the real service — that shortcut has killed Docker Desktop's backend twice. Always use `./stop_local.sh`, which filters by command name (`java|python|node|npm|uvicorn|gradlew`) with an explicit Docker denylist.

Java services are launched with plain `java -jar`, never `./gradlew bootRun` — `bootRun` keeps a second, uncapped Gradle build-supervisor JVM resident for the service's entire runtime (6 services → 12 resident JVMs instead of 6). `start_local.sh` builds jars once via a single short-lived `./gradlew --no-daemon :<module>:bootJar -x test` invocation per service, then runs the jars directly.

`start_local.sh` installs `ai_core/requirements.txt` into `ai_core/.venv` when it has changed since the last successful install. It runs every service inside a restart loop (`supervise`): a crashed process is restarted with backoff (2s doubling to 60s) and logged to `supervisor.log`. `stop_local.sh` touches `.gitoracle-stopping` and kills the loops (via their `*.supervisor.pid` files) **before** the port sweep — if you stop services any other way, the loops will restart them.

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
mypy ai_core/agents --ignore-missing-imports         # typecheck (CI gate, blocking)
pytest ai_core/tests/                                # full suite; no test calls a real LLM (test_agents_pipeline.py runs investigate→plan→fix in-process with mock_llm_server.py's answers)
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
python eval/setup_test_repo.py --push <https-remote-with-token>   # builds /tmp/gitoracle-eval-repo + golden/ (6 cases), force-pushes main and one case/<id> branch per bug to a GitHub fixture repo
python eval/run_evals.py --repo-url https://github.com/<owner>/<fixture> --output eval_report.json [--cases case1_npe,...]   # needs services running; talks to the gateway with GITORACLE_API_KEY
python eval/check_regression.py --report eval_report.json --min-score 0.75 --min-cause-score 0.5
```
Each case is on its own branch (`main` = correct code, all tests pass → bug commit → unrelated follow-up commit), because the test runner runs the whole suite: with every bug on one branch, no single fix could pass. The judge needs a large `max_tokens` — reasoning models return an empty reply at small limits. The golden repo must be on GitHub: `/trigger` rejects anything but `https://github.com/...` (`RepoRefValidator`), so a `file://` repo 400s. In CI this runs as `.github/workflows/eval.yml` (nightly + manual dispatch), configured by the `EVAL_LLM_*` / `GITORACLE_APP_ID` / `GITORACLE_APP_PRIVATE_KEY` secrets and the `EVAL_FIXTURE_REPO` variable listed in its header. It authenticates as the GitOracle GitHub App (installed on the fixture repo) both to push the golden repo and inside the pipeline, so jobs can reach `PR_OPENED` there.

GitHub App credentials (`GITHUB_APP_ID`, `GITHUB_INSTALLATION_ID`, `GITHUB_PRIVATE_KEY_PATH`) live in the root `.env`. `GitHubAppCredentials` (one copy in orchestrator, one in github-bot — github-bot can't depend on core; change both) takes all three from the environment when `GITHUB_APP_ID` is a real value (blank and `your_*` placeholders don't count), else from the deprecated `java-backend/github-bot/.env`, never mixing the two. `start_local.sh` makes a relative key path absolute (services run from `java-backend/`). `GITORACLE_REQUIRE_GITHUB_APP=true` turns missing credentials into a startup failure (the CI eval sets it).

Startup fails fast on missing config: every Java service refuses to boot without `GITORACLE_INTERNAL_TOKEN`, or with the public `change_me*`/`your_*` placeholder from `.env.example` (a `@PostConstruct` in each `InternalAuthFilter` / the gateway's `TenantContextFilter`; `@SpringBootTest`s set `gitoracle.internal-token` in their `properties`), the gateway likewise refuses a placeholder `GITORACLE_API_KEY` while the legacy key is enabled (`start_local.sh`'s `ensure_secret` replaces both placeholders with random values in `.env`), the error-ingestor treats a blank or placeholder webhook secret (`GITHUB_APP_WEBHOOK_SECRET`, `SENTRY_WEBHOOK_SECRET`, a tenant's own Sentry secret) as unset — `HmacVerifier.isUsableSecret`, so those webhooks are rejected and a startup warning says so; it still boots, and `start_local.sh` only prints a notice since the value must match the sender's settings — and every Python agent's `__main__` calls `shared/config.check_startup_config` (internal token; `LLM_BASE_URL` must be the full `/chat/completions` URL). `ci.yml` is the per-PR gate and does not run evals.
Note: `run_evals.py` drives cases through `POST /trigger`, which **defaults to `investigateFirst=true`** — so evals run the full investigate → plan → fix pipeline, not the direct-fix shortcut (pass `"investigateFirst": false` for that). Two gates: patch accuracy (LLM judge ≥7/10 vs the golden patch; stored as `accuracy`) and cause accuracy (the investigator's top commit == the bug commit; `--min-cause-score`). Cases are defined in `setup_test_repo.py`'s `CASES`: each bug is an edit of correct code (`_edit`), so the golden patch is generated (bug → correct) rather than hand-written, and `followups` keep the bug commit below HEAD.

`ci.yml`'s `pipeline-e2e` job runs eval case1 through the real stack with the LLM replaced by `ai_core/tests/mock_llm_server.py` (port 18089; it answers by the schema `title` that `llm_structured` puts in the system message) and asserts the timeline and patch (`scripts/ci/e2e_check.py`). It needs the fixture repo's `case/npe` branch, which the Golden Dataset Eval publishes. Both workflows share `scripts/ci/write_env.sh` and `scripts/ci/start_stack.sh`.

Kafka's host listener (`localhost:9092`) requires **SASL/PLAIN** (`KAFKA_SASL_USERNAME`/`KAFKA_SASL_PASSWORD`; `start_local.sh` generates the password, exports `KAFKA_SECURITY_PROTOCOL=SASL_PLAINTEXT`, and recreates a pre-SASL broker container); the internal `kafka:9094` listener used by containers stays PLAINTEXT. Java clients read it from `spring.kafka.properties` (copy them into any hand-built factory, as `OrchestratorApplication` does); Python clients from `shared/kafka_security.py`.

The Kafka broker stores every topic **lz4-compressed** (`KAFKA_COMPRESSION_TYPE`, `create-topics.sh`) regardless of producer settings, so Python consumers need `aiokafka[lz4]` (cramjam on aiokafka ≥0.14 — a bare `lz4` package does nothing). Without it an agent's consumer can't decode a single message; `tests/test_kafka_codecs.py` guards this.

## Architecture

### Two Kafka-naming conventions, one source of truth per language
`java-backend/git-oracle-core/.../kafka/KafkaTopics.java` centralizes constants for the **Java↔Java** events: `ERROR_INGESTED`, `FORENSICS_COMPLETE`, `FIX_GENERATED`, `TESTS_PASSED`, `TESTS_FAILED`, `PR_OPENED`, `JOB_ESCALATED`, `GITHUB_PR_EVENTS`, `AUDIT_EVENT`, etc. — dash-named topics (`error-ingested`, `fix-generated`, ...), used via `KafkaTopics.X` references.

The **Java→Python agent dispatch hops** (`job.events.investigate`, `job.events.plan`, `job.events.fix`, `job.events.review.received`, plus the dead-letter `job.events.dlq`) are dot-named. They're `KafkaTopics.JOB_*` constants in Java and `ai_core/shared/topics.py` in Python; `tests/test_topics_match_java.py` fails CI if a Python name drifts from Java. The one remaining literal is github-bot's `"github-pr-events"` (no core dependency).

### Two entry points into the pipeline, both real
1. **`error-ingested` → investigator → planner → fixer** (the "full pipeline"). Triggered by a github webhook, a sentry-style report, or the dashboard's "ask to fix" with the investigate toggle on. `OrchestratorService.handleErrorIngested` clones the repo (at a specific branch if requested), records HEAD as `baseCommit`, and dispatches to the investigator — whose top-ranked cause becomes `rootCommit`. `rootCommit` stays null until then; it is never pre-filled with HEAD.

2. **`job.events.fix` directly** (the "direct-fix" shortcut, `POST /api/v1/trigger` with `"investigateFirst": false`). Skips investigation with a synthesized plan — ~5x cheaper/faster, correct when a human already knows the fix, but no root-cause data and no file context beyond what's parsed from the instruction text. `/trigger` without that flag takes entry point 1 instead (that's what the eval harness does).

Every per-request override a job can carry — `human_instructions`, `target_repo`, `branch` — is threaded explicitly through every Kafka hop end-to-end rather than looked up from the DB, since these are per-request overrides, not part of a job's durable identity. If you add a new override, thread it the same way (event payload field → every downstream consumer reads it with a default) rather than persisting it on `AgentJob`.

### Job state goes through `JobStateService`, and every change is on the timeline
Never call `job.setState(...)` directly. `JobStateService.transition` (or `transitionFrom`, for listeners that may see events out of order) stamps `stateChangedAt`, stores the reason in `stateReason`, and appends a `JobEvent` (table `job_event`). It refuses to move a job out of a terminal state (`TERMINAL`: `PR_OPENED`, `PR_MERGED`, `PR_CLOSED`, `FAILED`) except along `TERMINAL_EXITS`: any of them → `REGENERATING` (a human asked for another fix cycle), `PR_OPENED` → `PR_MERGED`/`PR_CLOSED`, and `PR_CLOSED` → `PR_MERGED`. The normal path is QUEUED → INVESTIGATING → PLANNING (plan-snoop listener) → FIXING (fix-snoop listener) → TESTING → PR_OPENED → PR_MERGED or PR_CLOSED (what a human did to the PR), with FAILED/ESCALATED carrying a reason. The eval counts `PR_OPENED` and `PR_MERGED` as success. Agents add progress notes with `ai_core/shared/job_events.py: report_event(...)` → `POST /api/v1/jobs/{id}/events`; the dashboard reads `GET /api/v1/jobs/{id}/events`. State labels/colours for the UI live in one place: `dashboard/src/utils/jobState.ts`.

A PR's outcome reaches the job two ways, both through `OrchestratorService.recordPrOutcome` so they can't disagree (whichever arrives second is a no-op): the error-ingestor's `pull_request: closed` webhook, and `PrStatusReconciler`, which every `gitoracle.pr-reconciler.interval` (default 3m) asks github-bot's `GET /pull-request/status` about each job still in `PR_OPENED` — one GitHub API call per open PR. The reconciler exists because GitHub can't deliver webhooks to a local deployment; disable it with `GITORACLE_PR_RECONCILER_ENABLED=false`.

### Python agents are dual-mode: FastAPI server + Kafka consumer in one process
The investigator, planner and fixer each run a Kafka consumer for the app's lifetime via `shared/consumer_lifespan.run_consumer_with_app` (FastAPI lifespan; `on_event` is deprecated), using `shared/kafka_consumer.py` (topic per agent from `shared/topics.py`, e.g. fixer listens on `job.events.fix`). That loop never exits silently: a message that isn't a JSON object (undecodable, a tombstone, `[]`, ...) goes to `job.events.dlq` without reaching the handler; a message whose handler raises is copied to `job.events.dlq` with the error and its job is escalated (`job-escalated`, `source` = agent), then the loop moves on; if the consumer itself dies it is rebuilt with backoff. Every agent serves `GET /health` (no token needed; 503 when its consumer is down). Use `shared/kafka_producer.get_producer()` — one producer per process — never a fresh `KafkaEventProducer()` per message.

Separately, the orchestrator's `JobWatchdog` (every minute) escalates jobs stuck in a working state past `gitoracle.watchdog.limits` (e.g. INVESTIGATING 10m, FIXING 20m) with a reason, and counts them in `gitoracle_watchdog_escalations_total` (alert `JobsStalling` in `infrastructure/prometheus/rules/`). `JobWatchdog` and `PrStatusReconciler` are plain `@Scheduled` methods with no distributed lock, so they run on every orchestrator instance — run exactly one orchestrator (or add a lock such as ShedLock before scaling it out).

Agents have **no CORS middleware** (they're called server-to-server only; `tests/test_no_cors_on_agents.py` enforces it). A blanket `except Exception` in `ai_core/agents` or `ai_core/shared` must carry `# noqa: BLE001 — <why it's safe>` — CI runs `ruff --select BLE001` there; prefer catching the specific exception.

Each agent also exposes its own synchronous HTTP endpoint (`/fix`, `/plan`, etc.) that the Kafka handler calls into internally, and that other services also call directly (e.g. the fixer's own self-test call to test-runner, or the orchestrator's synchronous guardrails/test-runner/github-bot calls). Don't assume an agent is reachable only via Kafka or only via HTTP — check both call sites before changing a signature.

### Service map
| service | port | role |
|---|---|---|
| api-gateway (Java) | 8080 | single entry point; fail-closed `X-API-Key` auth, API-key→tenant resolution, centralized CORS (`TenantContextFilter`), routes `/webhook/**`→error-ingestor, `/api/v1/risk`→git-forensics, `/api/v1/**`→orchestrator |
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

### Tenancy is derived from the API key, never from a header
`X-Tenant-ID` used to be read straight off the inbound request and defaulted to the zero UUID — combined with one shared `GITORACLE_API_KEY`, any authenticated caller could read or mutate any tenant's data by changing a header. Now `TenantContextFilter` (gateway) resolves the tenant from the presented key against the `api_keys` table and **strips** any client-supplied `X-Tenant-ID`/`X-Scopes` before routing. Downstream, the orchestrator's own `TenantContextFilter` puts that value in a request-scoped `TenantContext`, and every tenant-owned query carries a `tenantId` predicate.

Keys are `gor_<12-hex prefix>_<64-hex secret>`; only a SHA-256 hash is stored, located by the indexed public prefix and compared with `MessageDigest.isEqual`. Mint/list/revoke via `/api/v1/admin/api-keys` (requires the `platform:admin` scope, enforced at the gateway). Two things to know:
- **Revocation is not instant** — the gateway caches key rows for `GITORACLE_API_KEY_CACHE_TTL_SECONDS` (default 60). Restart the gateway to purge immediately.
- **`GITORACLE_API_KEY` is legacy** — a single shared secret resolving to the default tenant with `platform:admin`. It exists only to bootstrap the first real key; set `GITORACLE_LEGACY_API_KEY_ENABLED=false` afterwards. `scripts/setup_production_config.sh` does that whole switch (mints a real key, verifies it, stores it as `GITORACLE_API_KEY`, then disables the legacy key; also moves the App credentials into the root `.env` and sets the repository allow-list). The gateway logs a warning every boot while it is on.

**Repositories are tenant-scoped too** (`RepoAccessService`, table `tenant_allowed_repos`, slugs from `RepoNames.slug`): `/trigger`, `/jobs`, the commit explorer and `/api/v1/risk` (git-forensics asks the orchestrator's `GET /api/v1/repos`) reject a repo — or PR target — not on the caller tenant's allow-list with 403, and `handleErrorIngested` FAILs such jobs before cloning, which covers the GitHub/Sentry webhooks. An empty list allows nothing. `GITORACLE_ALLOWED_REPOS` seeds the default tenant (CI sets it to the fixture repo); `/api/v1/admin/repos` manages any tenant's list. Sentry webhooks are verified with the tenant's own secret (`POST /api/v1/admin/tenants/{id}/sentry-secret`); the shared `SENTRY_WEBHOOK_SECRET` only still works for the default tenant. Eval runs carry a `tenant_id`.

**People sign in with GitHub; API keys are for scripts.** `AuthController` (orchestrator, `/api/v1/auth`) runs the GitHub App's web sign-in using `GITHUB_APP_CLIENT_ID`/`_SECRET` and creates a `UserAccount` and a `UserSession`; the user's GitHub token is dropped after one `/user` call. The browser gets an `HttpOnly`, `SameSite=Strict` cookie holding a random token, and only its hash is stored. The gateway's `SessionAuthenticator` resolves the cookie (table `user_sessions`, plain JDBC, same 60s cache, evicted on logout) into the same `X-Tenant-ID`/`X-Scopes` headers as a key, plus `X-User-ID`/`X-Session-ID`, and removes the cookie before routing; an `X-API-Key` takes precedence over it. Cookie-authenticated requests that change state must carry an `Origin` from `GITORACLE_ALLOWED_ORIGINS` (the CSRF check in the gateway's `TenantContextFilter`), so that setting can't be `*`. Users are identified by GitHub id, never login.

**A tenant is an organisation, and a person's access comes from a membership (`OrgMembership`, role `OrgRole`: VIEWER, MEMBER, ADMIN, OWNER).** A person can belong to several; the session holds the active one and that role's scopes (`jobs:read`, `jobs:write`, `org:admin`, `org:owner`), and `POST /api/v1/auth/switch-org` moves it. Platform admin is separate from any role: `GITORACLE_ADMIN_GITHUB_IDS` (numeric ids) adds `platform:admin` and owns the default tenant. To sign in you must be a platform admin, on `GITORACLE_INVITED_GITHUB_USERS` (which gives you an organisation of your own), or invited (`OrgInvitation`, accepted automatically at sign-in, tied to the numeric GitHub id looked up when invited, 7-day expiry). A membership with no inviter came from those lists and lasts only while they allow it; the default tenant's only for a platform admin (`LoginService.usableMemberships`). `OrganizationService` holds the rules: nobody grants or changes a role above their own, an organisation keeps at least one owner, and losing a role ends that person's sessions (and removal or viewer status revokes the API keys they made). Those changes are in `audit_log` (`AuditLog`, `GET /api/v1/org/audit`). Existing users became owners once, in the upgrade (`moveExistingUsersIntoOrganizations`, which only runs while there are no memberships, or a removed member would return at each restart). The gateway caches a session for `GITORACLE_SESSION_CACHE_TTL_SECONDS` (default 5). **Each organisation connects its own GitHub App installation** (`tenants.github_app_installation_id`, read through `GitHubInstallations`), and only the default organisation falls back to `GITHUB_INSTALLATION_ID`. A job in another organisation with no connection FAILs before cloning. `GitHubConnectService` runs the flow: an owner starts it, GitHub's redirect to the App's Setup URL (`/api/v1/auth/github/setup`) carries an `installation_id` anyone could type, so nothing is connected until a GitHub sign-in shows the same person can access that installation through `GET /user/installations`, and no other organisation uses it. Org admins allow repositories with `POST /api/v1/org/repos`, only if that installation can see them; a platform admin can still use `/api/v1/admin/repos`. github-bot takes `installationId` per request (blank means its default; the orchestrator always sends one). Signed GitHub webhooks go to the organisation whose installation sent them, and an installation nobody connected is dropped. Pipeline clones are still unauthenticated, so only public repositories run end to end. `UsageLimits` caps jobs and tokens per calendar month (`GITORACLE_ORG_MONTHLY_JOBS`/`_TOKENS`, or a platform admin's `PUT /api/v1/admin/tenants/{id}/limits`; 429 at `/trigger`, FAILED for webhooks); the default organisation has none unless set. Give a new organisation its first owner with `POST /api/v1/admin/tenants/{id}/invitations`. In the dashboard, `AuthContext` exposes `role`, `canWrite`, `canManageOrg` and `isPlatformAdmin` to hide what the server would refuse (the server still decides), and `RepoContext` reads the real allow-list from `GET /api/v1/repos`; only the active repo stays in the browser.

The login page shows GitHub sign-in when `GET /api/v1/auth/config` says `githubLogin` (App credentials set **and** someone invited), and the API key form only otherwise, so a new fork can still get in; to recover from broken GitHub sign-in, blank `GITHUB_APP_CLIENT_ID` and restart. Signed-in users manage their tenant's keys on the dashboard's API keys page (`ApiKeyController`, `/api/v1/keys`): session only (it requires `X-User-ID`, so a leaked key can't mint more), never `platform:admin`, always expiring (default 90 days, max 365), and a member sees and revokes only their own while an org admin sees all. `/api/v1/admin/api-keys` remains for operators and `scripts/setup_production_config.sh`.

**What each route requires is decided in one place: `RouteAccess` (core), enforced by the gateway's `TenantContextFilter`.** An unlisted `GET` needs `jobs:read` (or `jobs:write`), anything else `jobs:write`; `platform:admin` implies both and `org:admin`. `/api/v1/admin/**` needs platform admin, `/api/v1/org/**` writes and `POST /api/v1/evals` an org admin, `/api/v1/keys` a signed-in person (who can also write, to change anything), and `POST .../events`, `.../prompt-version` and `.../budget/.../record` are internal: agents reach them directly on the orchestrator with the internal token, and the gateway refuses them for everyone. **Adding a route fails `RouteAccessMatrixTest` until you classify it there.** Services behind the gateway don't recheck read/write scopes; they're on loopback behind the internal token.

The dashboard's System status page (`GET /api/v1/admin/system/status`, `SystemStatusService`) is read-only on purpose: it probes every service and asks each agent for `GET /runtime-config` (`shared/runtime_config.py`, internal token required, hosts only and never credentials), and flags agents that disagree. Settings change only through `.env` and a restart, so a stolen admin session can't redirect code to another server. There are no agent or guardrail switches: the pipeline's rules are fixed. To use a local LLM, set `LLM_BASE_URL` and `LLM_MODEL_NAME`, blank `OPENAI_API_KEY` (it's sent to whatever server is configured), and keep `LLM_FALLBACK_BASE_URL` unset unless code may go there when the LLM fails.

The dashboard's webhook simulator can't sign a webhook without holding the secret, so it posts to `POST /api/v1/admin/webhook-simulator` (error-ingestor's `WebhookSimulatorController`, with its own gateway route). It requires `platform:admin`, checked at the gateway and again in the controller, accepts only failed `workflow_run` payloads, and shares `WebhookController.handleWorkflowRun` with real deliveries. Forged PR outcomes and review comments stay impossible: those events still need a real signature.

`TenantContext` is **request-scoped only**. Kafka listeners run on consumer threads with no request, so they must take the tenant from the event payload (`ErrorIngestedEvent.tenantId`); `TenantContext.requireTenantId()` throws there rather than silently defaulting.

### Storage
Postgres+pgvector (job state, escalations, PR outcomes, eval runs, prompt versions, episodic/semantic agent memory) · Neo4j (repo dependency/risk graph) · Qdrant (RAG context vectors) · Redis (gateway rate limiting, prompt-registry cache) · Langfuse/Prometheus/Grafana/Kafka UI (observability).

Agent memory's embeddings come from `EMBEDDINGS_BASE_URL` (any OpenAI-compatible `/embeddings` endpoint — Ollama, LM Studio, OpenAI, ...; `shared/embeddings.py`), independent of the chat LLM. Unset means memory is off: nothing is stored or recalled. Vectors are stored at 384 dimensions and tagged with their model; recall only compares same-model vectors. Memories are keyed by repository (`memory.repo_key`), never by a job's workspace path.

### Memory constraints are load-bearing, not incidental
The full stack targets 8GB RAM minimum: `java-backend/gradle.properties` (`org.gradle.daemon=false`, capped `jvmargs`), `start_local.sh`'s per-service `-Xmx`/`-XX:MaxMetaspaceSize` JVM args, and a `mem_limit` on every container in `docker-compose.infra.yml` all exist specifically to prevent Docker Desktop from being OOM-killed on smaller machines — this has happened in practice. Don't remove these caps to "simplify" a service's startup.

### Docker is required for test execution — there is no unsandboxed fallback
`test-runner` runs a cloned repo's test suite in a Docker container. It used to silently fall back to running that repo's own build/test command directly on the host whenever Docker was unavailable for any reason — since that command (`mvn test`, `npm ci`, `pip install -r requirements.txt`, `cargo test`, ...) executes the repo's own build scripts, that fallback was arbitrary code execution as the service's own user. It's gone: a `docker info` preflight at startup and a runtime check on every job both fail closed (503 at startup, a failed `TestResult` per job) instead. If Docker Desktop stops while services are running, test execution stops working rather than silently running unsandboxed — that's intentional, not a bug. A native fallback still exists for local dev behind two env vars (`GITORACLE_ALLOW_UNSAFE_NATIVE_TESTS=true` **and** `GITORACLE_TESTRUNNER_TRUSTED_REPOS` listing the exact repo URL) — never set these where an untrusted or public repo could be cloned.

## Project structure
- `java-backend/` — 6 Java microservices (each its own gradle subproject) + `git-oracle-core` shared library.
- `ai_core/` — 7 Python FastAPI agents (`agents/`), shared LLM/kafka/memory/prompt-registry helpers (`shared/`), regression suite (`tests/`).
- `dashboard/` — React + TypeScript + Vite web UI (`dashboard/src/api/client.ts` is the single axios client every page uses; base URL via `VITE_API_URL`). `pages/DeveloperGuide.tsx` (`/guide`, linked at the top of every page, readable without signing in) lists the ways to use GitOracle: keep it in step when setup, sign-in or job entry points change.
- `cli/` — the `gitOracle` click-based terminal client.
- `eval/` — golden-dataset generator (`setup_test_repo.py`) and harness (`run_evals.py`, `check_regression.py`).
- `infrastructure/` — prometheus/grafana config.
- `llm-server/` — deprecated; inference is now routed to any openai-compatible endpoint via `LLM_BASE_URL`, not a local `llama.cpp` server.
