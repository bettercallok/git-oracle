# GitOracle security audit — November 2026

Scope: the whole repository — the api-gateway and the five servlet services
(`java-backend/`), the seven Python agents and shared helpers (`ai_core/`),
the dashboard, CLI, eval harness, compose files, CI workflows and scripts.
Method: I traced every trust boundary by hand: external API → gateway →
internal services, GitHub/Sentry webhooks → pipeline, LLM output →
filesystem/git, cloned repo → sandbox, and GitHub App credentials →
responses and logs. Each finding below was confirmed by reading the code
path end to end. Each fixed finding has a regression test that fails
without the fix.

The codebase has clearly been through earlier hardening, and most of it holds
up. That earlier work covered key-derived tenancy, header stripping,
canonicalised admin-path matching, fail-closed tokens, `RepoRefValidator`
at every git call site, the Docker sandbox, the guardrails allowlist and
diff parser, and `safe_paths`. The findings here are what was left.

## Summary

| # | Severity | Finding | Status |
|---|----------|---------|--------|
| 1 | **Critical** | `X-Internal-Token` bypass on every Java service via `/health/../<route>` | Fixed |
| 2 | **High** | GitHub App installation token leaked into job timeline, logs and argv | Fixed |
| 3 | **High** | Any GitHub user could steer any tenant's job through a PR/issue comment | Fixed |
| 4 | **High** | test-runner `jobId` path traversal → recursive delete of arbitrary directories | Fixed |
| 5 | Medium | Cross-tenant budget tampering: `POST /api/v1/budget/{jobId}/record` | Fixed |
| 6 | Medium | Fixer cloned unvalidated `repo_url` (incl. `file://`) and `rmtree`d unvalidated `repo_path` | Fixed |
| 7 | Medium | Guardrails denylist allowed `.github/actions/**` and `.gitmodules` | Fixed |
| 8 | Low | PR-outcome stats poisonable from any repo; prompt-version writes not ownership-checked | Fixed |
| 9 | Low | `/trigger` accepted an unvalidated `targetRepo` | Fixed (format only — see D1) |
| D1 | **High (design)** | No tenant→repository binding for the shared GitHub App installation | Open — needs a decision |
| D2 | Medium (design) | One Sentry webhook secret shared by all tenants; tenant taken from the URL | Open |
| D3 | Low | `/api/v1/risk` and `/api/v1/evals` are not tenant-scoped | Open |
| D4 | Low | Kafka is unauthenticated PLAINTEXT (loopback-bound) | Open (known, C7) |

---

## Fixed

### 1. Critical — internal-token bypass via dot segments under an exempt prefix

**Where:** `InternalAuthFilter` in orchestrator, error-ingestor, git-forensics,
test-runner and github-bot.

Each filter exempted `/actuator`, `/health` and `/api/v1/health` with
`path.startsWith(exempt + "/")` on `request.getRequestURI()`. That is the
*raw* request line. Tomcat maps the request on its *normalised* form. So:

```
GET /health/../pull-request/status?url=...     → exempt by prefix, dispatched to /pull-request/status
POST /actuator/..;/test                          → exempt, dispatched to test-runner /test
POST /health/%2e%2e/api/v1/trigger               → exempt, dispatched to the orchestrator
```

None of these requests carry `X-Internal-Token`. This undid the
"every service authenticates itself" guarantee (C5) for anyone who can
reach a service port: a container network, a misconfigured
`SERVER_BIND_HOST`, or another local user. Chained with #4 it gives
**unauthenticated arbitrary directory deletion**, and it gives
unauthenticated job creation and PR operations. The gateway was not
affected, because its exemption already required `RequestPaths.isCanonical`.

**Fix:** the exemption now applies only to a URI with no `%`, `;`, `\`,
empty segments or dot segments. Anything else is authenticated normally.
**Test:** `github-bot/.../security/InternalAuthPathBypassTest` boots a real
Tomcat and sends raw requests over a socket. MockMvc does not normalise
paths, and HTTP clients collapse `..` before sending, so neither could
reproduce the bug.

### 2. High — installation token leaked to tenants and logs

**Where:** `github-bot/GitHubController.createPullRequest`.

The clone URL was `https://x-access-token:<TOKEN>@github.com/<repo>.git`.
When the clone failed, `runCommand` threw
`Command failed: git clone ... https://x-access-token:<TOKEN>@github.com/...`.
The controller logged that message and returned it as `error`. The
orchestrator stored it as the job's `stateReason` and as a `JobEvent`
(`"PR creation failed: ..."`), and serves both from `GET /api/v1/jobs/{id}`
and `/events`. A tenant can force a clone failure on purpose, for example
with a `targetRepo` that lacks the requested `branch`. That tenant then
reads a token with write access to *every* repository the App is installed
on, valid for an hour. The token also sat in the process argv (visible in
`ps`), and stayed in `.git/config` of every clone left behind by a failed
run (cleanup ran only on success).

**Fix:** the token now reaches git only as an `http.extraheader` passed in
`GIT_CONFIG_COUNT/KEY_0/VALUE_0` environment variables, the same way
`actions/checkout` does it. It no longer appears in the URL, argv, or
`.git/config`. Error messages are redacted (the token, its basic-auth
form, and any `user:pass@` in a URL) before they are logged or returned.
The work directory is removed in `finally`.
**Test:** `CredentialRedactionTest` checks the redaction. It also checks,
against a real `git`, that the injected header is seen by git and never
written to the repository.

### 3. High — PR comments from anyone could hijack any tenant's job

**Where:** `error-ingestor/WebhookController.handleReviewComment` and
`orchestrator/OrchestratorService.handleReviewReceived`.

A job was found from a `<!-- gitoracle:job_id:<uuid> -->` marker in the
PR **or issue** body. Every GitOracle PR body prints that marker, so it is
public. The orchestrator then built the new fix cycle with
`repo_url` and `target_repo` taken **from the webhook payload's
repository**, not from the job. Nobody checked who wrote the comment. As a
result:

- Any GitHub account that can comment on a public repo's GitOracle PR could
  supply the Fixer's `human_instructions`. The regenerated patch is pushed
  onto the PR branch with the App's credentials. That is a
  review-then-swap attack on a PR a maintainer has already looked at.
- An attacker could paste a victim job's marker into an issue on *their
  own* repository (any repo whose webhooks reach this ingestor). That
  re-pointed the victim tenant's job, and its cloned workspace, at the
  attacker's repository.
- GitOracle's own bot comments were also eligible input.

**Fix:** the ingestor now forwards a comment only if its
`author_association` is `OWNER`, `MEMBER` or `COLLABORATOR`, and never
from a `Bot` sender. Issue comments are forwarded only when the issue is a
pull request. The event now carries the `pr_url` the comment was made on.
The orchestrator acts only if that URL is the job's recorded `prUrl`, and
always uses the job's own repo and PR repository.
**Tests:** `ReviewCommentAuthorizationTest` (ingestor) and three
`OrchestratorServiceTest` cases (foreign PR, missing PR URL, and the job's
own PR → the job's repo wins over the payload's).

### 4. High — test-runner `jobId` traversal deletes arbitrary directories

**Where:** `TestRunnerController.runTest`.

`Path.of(workspaceRoot, jobId)` used the request's `jobId` as given. That
path is then passed to `deleteDirectory` (recursive) both before cloning
and in `finally`. `{"jobId": "../../home/app/data"}` deleted that
directory as the test-runner user. Together with #1, no token was needed.

**Fix:** `jobId` must match `[A-Za-z0-9][A-Za-z0-9_-]{0,63}`. On top of
that, the resolved path must be a direct child of the workspace root.
**Test:** `JobIdContainmentTest` sends a real `runTest` with `../victim`
and checks that the victim directory survives.

### 5. Medium — cross-tenant budget tampering

**Where:** `orchestrator/token/BudgetController.recordUsage`.

Agents use this route, but it is also reachable through the gateway, and
it had no ownership check. Any tenant could post
`{"tokensUsed": 2000000000}` against another tenant's (public) job id to
get that job escalated as `TOKEN_BUDGET_EXCEEDED`. Negative values
refunded a tenant's own budget.

The root cause also affected `recordJobEvent`. `TenantContextFilter` fills
in the default tenant when no header is present, so
`TenantContext.tenantId() != null` was always true. That meant the code
could never tell an agent from a gateway caller. A side effect: agents'
timeline notes on jobs outside the default tenant were rejected with 404.

**Fix:** `TenantContext.isAssertedByGateway()` is now true only when a
well-formed `X-Tenant-ID` arrived. The gateway sets that header on every
key-authenticated request and strips client copies. Gateway callers must
own the job, for budget records, job events and prompt-version writes.
Agents are unaffected. `tokensUsed` must be between 0 and 1,000,000.
**Tests:** `BudgetRecordAuthorizationTest`, plus a new `TenantContextFilterTest` case.

### 6. Medium — fixer cloned arbitrary URLs and deleted arbitrary paths

**Where:** `ai_core/agents/fixer/main.py`.

`git.Repo.clone_from(request.repo_url, ...)` ran with no validation, and
the request model even documented `repo_url` as "GitHub or file://". A
`file://` URL (or a bare local path) clones a repository from the host's
own filesystem into the checkout the fixer reads into the LLM prompt.
Separately, `shutil.rmtree(request.repo_path)` ran whenever that path
lacked a `.git`.

**Fix:** the new `shared/repo_refs.py` mirrors `RepoRefValidator`
(`https://github.com/<owner>/<repo>` only, with plain branch names). A
clone and its `rmtree` happen only for a `repo_path` strictly inside
`GITORACLE_WORKSPACE_ROOT` (default `/tmp/gitoracle-workspaces`).
**Test:** `ai_core/tests/test_repo_refs.py`.

### 7. Medium — guardrails denylist gap

`.github/workflows/**` was denied, but local composite actions
(`uses: ./.github/actions/x`) run inside those same workflows with the
same secrets, and they were not denied. `.gitmodules` was not denied
either; it can re-point a submodule that CI then checks out and builds.
Both are now in `DENIED_GLOBS`, which covers both reading and patch
validation. **Test:** `test_repo_refs.py::test_guardrails_deny_ci_adjacent_paths_even_when_authorized`.

### 8. Low — outcome/prompt statistics poisoning

`recordPrOutcome` protected job *state* by matching the PR URL, but it
still inserted a `pr_outcomes` row for any PR that carried the marker. A
stranger closing their own PR could therefore lower another tenant's
merge rate. Outcomes are now recorded only for PRs on the job's PR
repository. Prompt-version writes from gateway callers now require
ownership (#5).

### 9. Low — `targetRepo` unvalidated at `/trigger`

`targetRepo` is checked against owner/repo syntax at the entry point, like
`repoUrl` and `branch`, so a bad value gets an immediate 400 instead of
failing only at github-bot after the whole pipeline has run.

---

## Open — needs a decision

### D1. High (design) — no tenant→repository binding

Every tenant shares one GitHub App installation (`GITHUB_INSTALLATION_ID`),
and nothing restricts which repositories a tenant's requests may name.
Any tenant API key can therefore:

- read commit lists and diffs of any repo the installation can see, private
  ones included (`CommitController`, `/api/v1/commits?repo=...`);
- send a job's PR to any such repo (`targetRepo`), which pushes a
  `gitoracle-fix-*` branch there with content the tenant largely controls
  through its instructions.

Tenancy is enforced for GitOracle's own data, but not for the GitHub
resources that data points at. The right fix is a per-tenant allowlist or
installation mapping. `TenantConfig.githubAppInstallationId` already
exists but is unused. With that in place, every `repo`, `repoUrl` and
`targetRepo` would be checked against the caller's own allowlist. This
changes product behaviour, which is why it is not part of this change.
Until then, treat every tenant as able to read every installed repo.

### D2. Medium — shared Sentry secret, tenant from the URL

`/webhook/{tenantId}/sentry` authenticates with one `SENTRY_WEBHOOK_SECRET`
and takes the tenant from the path. Anyone who holds that secret can file
jobs under any tenant. It needs per-tenant secrets, like the per-installation
webhook secret work the gateway's `TenantContextFilter` already notes as
deferred.

### D3. Low — unscoped global reads

`GET /api/v1/risk` (the Neo4j hotspot and developer graph) and
`GET`/`POST /api/v1/evals` have no tenant predicate, and `EvalRun` has no
tenant column. Any tenant can read the risk data, and can read or write
the eval table.

### D4. Low — Kafka PLAINTEXT

Already tracked as C7. The broker is loopback-bound, but any local process
can publish to `job.events.fix` and similar topics, which bypasses every
HTTP-layer check. Enable SASL/mTLS before running on a shared host.

### Smaller observations

- `orchestrator/application.yml` has `show-sql: true`, which writes every
  SQL statement to stdout (without bound values).
- Gateway `/actuator/prometheus` is unauthenticated (route and tenant
  labels). It is fine on loopback, but should not be published.
- test-runner and the orchestrator both use `/tmp/gitoracle-workspaces/<jobId>`
  by default on a local (non-container) run. test-runner deletes that
  directory before and after every test, so it removes the orchestrator's
  clone of the same job. This is a correctness bug rather than a security
  one, but it explains workspaces that go missing in local runs.
