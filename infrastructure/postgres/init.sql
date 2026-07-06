-- ============================================================
-- GitOracle — PostgreSQL Initialisation Script
-- Runs automatically on first container startup
-- ============================================================

-- ─── Extensions ──────────────────────────────────────────────
CREATE EXTENSION IF NOT EXISTS vector;           -- pgvector: semantic embeddings
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";      -- UUID generation
CREATE EXTENSION IF NOT EXISTS pg_trgm;          -- trigram index for text search

-- ─── Create Langfuse database ────────────────────────────────
-- (Langfuse uses its own isolated DB on the same Postgres instance)
-- Note: current superuser (POSTGRES_USER) automatically owns the database.
SELECT 'CREATE DATABASE langfuse'
WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'langfuse')\gexec

-- ============================================================
-- TENANTS
-- One row per GitHub organisation using GitOracle
-- ============================================================
CREATE TABLE IF NOT EXISTS tenants (
    id                              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    org_name                        TEXT UNIQUE NOT NULL,
    github_app_installation_id      TEXT,
    -- Tenant-level overrides (null = use global default)
    token_budget_limit              INT,
    causal_confidence_threshold     FLOAT,
    max_fix_attempts                INT,
    escalation_slack_webhook        TEXT,
    escalation_email                TEXT,
    config                          JSONB NOT NULL DEFAULT '{}',
    is_active                       BOOLEAN NOT NULL DEFAULT true,
    created_at                      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at                      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

COMMENT ON TABLE tenants IS 'One row per GitHub organisation. All queries are filtered by tenant_id.';

-- ============================================================
-- AGENT JOBS
-- Core job record — one row per production error processed
-- ============================================================
CREATE TABLE IF NOT EXISTS agent_job (
    id                  UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    tenant_id           UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,

    -- Error context
    error_id            TEXT NOT NULL,
    error_type          TEXT,
    repo                TEXT NOT NULL,
    affected_file       TEXT,
    affected_line       INT,
    raw_payload         JSONB,                      -- original webhook payload (for replay)
    error_embedding     vector(384),                -- semantic embedding for dedup

    -- State machine
    state               TEXT NOT NULL DEFAULT 'INGESTED',
    -- Valid states: INGESTED | FORENSICS | INVESTIGATING | PLANNING |
    --               FIXING | TESTING | PR_OPENED | REVIEWING | DONE |
    --               AWAITING_HUMAN_CONFIRMATION | ESCALATED | CLOSED

    -- Investigation results
    root_commit         TEXT,
    causal_score        FLOAT,                      -- 0.0–1.0 confidence

    -- Planning results
    fix_strategy        TEXT,                       -- FixStrategy enum value
    planned_files       TEXT[],                     -- files Planner approved to modify

    -- Fix results
    fix_patch           TEXT,
    patch_diff          TEXT,
    attempts            INT NOT NULL DEFAULT 0,
    quality_score       FLOAT,                      -- composite patch quality 0.0–1.0
    coverage_delta      FLOAT,                      -- test coverage change

    -- PR details
    pr_url              TEXT,
    pr_number           INT,
    pr_branch           TEXT,

    -- Token budget tracking
    token_budget_used   INT NOT NULL DEFAULT 0,
    token_budget_limit  INT NOT NULL DEFAULT 50000,

    -- Escalation
    escalation_report   JSONB,                      -- structured report when escalated
    escalation_reason   TEXT,                       -- 'MAX_ATTEMPTS' | 'LOW_CONFIDENCE' | 'BUDGET' | 'GUARDRAIL'

    -- Agent metadata
    agent_version       TEXT,
    investigator_trace_id TEXT,                     -- Langfuse trace ID
    fixer_trace_id      TEXT,
    reviewer_trace_id   TEXT,

    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

COMMENT ON TABLE agent_job IS 'One row per production error processed. Central job record tracked by the Orchestrator state machine.';
COMMENT ON COLUMN agent_job.raw_payload IS 'Original Sentry/PagerDuty webhook payload. Used by /trigger/replay endpoint.';
COMMENT ON COLUMN agent_job.error_embedding IS '384-dim sentence-transformer embedding for semantic deduplication.';
COMMENT ON COLUMN agent_job.token_budget_used IS 'Running total of tokens consumed across all LLM calls for this job.';

-- ─── Indexes ─────────────────────────────────────────────────
-- Semantic dedup: find similar errors by vector distance
CREATE INDEX IF NOT EXISTS idx_agent_job_embedding
    ON agent_job USING ivfflat (error_embedding vector_cosine_ops)
    WITH (lists = 100);

-- Orchestrator queries: jobs by state
CREATE INDEX IF NOT EXISTS idx_agent_job_state
    ON agent_job (tenant_id, state, created_at DESC);

-- Repo + state queries (dashboard)
CREATE INDEX IF NOT EXISTS idx_agent_job_repo_state
    ON agent_job (tenant_id, repo, state);

-- Active jobs (for dedup check — only look at non-terminal states)
CREATE INDEX IF NOT EXISTS idx_agent_job_active
    ON agent_job (tenant_id, error_id)
    WHERE state NOT IN ('DONE', 'CLOSED', 'ESCALATED');

-- ============================================================
-- AUDIT LOG
-- Immutable, append-only record of every AI decision
-- No UPDATE or DELETE allowed (enforced by rules below)
-- ============================================================
CREATE TABLE IF NOT EXISTS audit_log (
    id              BIGSERIAL PRIMARY KEY,
    job_id          UUID REFERENCES agent_job(id) ON DELETE SET NULL,
    tenant_id       UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,

    -- Event classification
    event_type      TEXT NOT NULL,
    -- Valid types:
    --   WEBHOOK_RECEIVED | DEDUP_HIT | STATE_CHANGED | LLM_CALL |
    --   PATCH_GENERATED  | PATCH_SCANNED | TEST_STARTED | TEST_RESULT |
    --   PR_CREATED       | REVIEW_RECEIVED | REVIEW_REPLIED |
    --   MEMORY_STORED    | MEMORY_RECALLED | BUDGET_WARNING |
    --   GUARDRAIL_TRIGGERED | ESCALATED | FEEDBACK_RECEIVED

    actor           TEXT NOT NULL,          -- which service/agent produced this event
    payload         JSONB NOT NULL,         -- full event data

    -- LLM-specific metadata (null for non-LLM events)
    prompt_hash     TEXT,                   -- SHA256 of prompt (links to prompt_version)
    prompt_version_id UUID,                 -- which prompt version was active
    tokens_used     INT,
    latency_ms      INT,
    langfuse_trace_id TEXT,                 -- Langfuse trace for full prompt/response

    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

COMMENT ON TABLE audit_log IS 'Immutable audit trail. Every LLM call, state change, and agent decision is recorded here. Required for enterprise compliance.';

-- Prevent any modification or deletion (append-only)
CREATE RULE audit_no_update AS ON UPDATE TO audit_log DO INSTEAD NOTHING;
CREATE RULE audit_no_delete AS ON DELETE TO audit_log DO INSTEAD NOTHING;

-- ─── Indexes ─────────────────────────────────────────────────
CREATE INDEX IF NOT EXISTS idx_audit_log_job
    ON audit_log (job_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_audit_log_tenant_type
    ON audit_log (tenant_id, event_type, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_audit_log_created
    ON audit_log (created_at DESC);

-- ============================================================
-- PROMPT VERSIONS
-- Versioned, hot-reloadable prompts for all AI agents
-- ============================================================
CREATE TABLE IF NOT EXISTS prompt_version (
    id          UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    tenant_id   UUID REFERENCES tenants(id) ON DELETE CASCADE,
    -- tenant_id NULL = global default (applies to all tenants unless overridden)

    agent_name  TEXT NOT NULL,
    -- Valid agents: investigator | fixer | reviewer | planner | guardrails

    prompt_key  TEXT NOT NULL,
    -- Valid keys per agent:
    --   investigator: system | narration_user
    --   fixer:        system | reason_user | act_user | escalation_user
    --   reviewer:     system | reply_user
    --   planner:      system | classify_user
    --   guardrails:   injection_detect | patch_scan

    version     INT NOT NULL DEFAULT 1,
    content     TEXT NOT NULL,
    changelog   TEXT,                   -- what changed from previous version
    is_active   BOOLEAN NOT NULL DEFAULT false,
    eval_score  FLOAT,                  -- average score from eval harness (0.0–1.0)
    eval_runs   INT NOT NULL DEFAULT 0, -- number of eval cases scored
    created_by  TEXT NOT NULL DEFAULT 'system',
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Unique index supporting NULLable tenant_id (global vs tenant-scoped prompts)
-- UNIQUE INDEX (not constraint) is required to use COALESCE expression
CREATE UNIQUE INDEX IF NOT EXISTS idx_prompt_version_unique
    ON prompt_version (agent_name, prompt_key, version, COALESCE(tenant_id, '00000000-0000-0000-0000-000000000000'));

COMMENT ON TABLE prompt_version IS 'Versioned prompt registry. Agents fetch active prompts at request time via the Prompt Registry service. Hot-reload without restart.';
COMMENT ON COLUMN prompt_version.tenant_id IS 'NULL = global default. Non-null = tenant-specific override. Tenant overrides take precedence.';

CREATE INDEX IF NOT EXISTS idx_prompt_active
    ON prompt_version (agent_name, prompt_key, is_active)
    WHERE is_active = true;

-- ============================================================
-- AGENT MEMORY
-- Episodic, semantic, and procedural memory across jobs
-- ============================================================
CREATE TABLE IF NOT EXISTS agent_memory (
    id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    tenant_id       UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    repo            TEXT NOT NULL,

    memory_type     TEXT NOT NULL,
    -- episodic:   "On 2025-06-10, fixed NullPointerException in PaymentService with Optional..."
    -- semantic:   "This repo uses constructor injection, not field injection"
    -- procedural: "Test command: mvn test -Pintegration. Requires: docker-compose up test-deps first"

    content         TEXT NOT NULL,          -- human-readable memory text
    embedding       vector(384),            -- for semantic similarity retrieval
    confidence      FLOAT NOT NULL DEFAULT 1.0,  -- 0.0=negative/wrong, 1.0=confirmed correct
    source_job_id   UUID REFERENCES agent_job(id) ON DELETE SET NULL,
    metadata        JSONB NOT NULL DEFAULT '{}',

    -- Reinforcement tracking
    recall_count    INT NOT NULL DEFAULT 0,
    last_recalled   TIMESTAMPTZ,
    feedback_outcome TEXT,                  -- PR_MERGED | PR_REJECTED | FIX_REVERTED

    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

COMMENT ON TABLE agent_memory IS 'Three-tier agent memory (episodic/semantic/procedural). Retrieved by semantic similarity before each agent invocation. Reinforced by PR feedback outcomes.';

CREATE INDEX IF NOT EXISTS idx_agent_memory_embedding
    ON agent_memory USING ivfflat (embedding vector_cosine_ops)
    WITH (lists = 50);

CREATE INDEX IF NOT EXISTS idx_agent_memory_repo_type
    ON agent_memory (tenant_id, repo, memory_type, confidence DESC);

-- ============================================================
-- EVAL RESULTS
-- Output from the evaluation harness — one row per case per run
-- ============================================================
CREATE TABLE IF NOT EXISTS eval_result (
    id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    job_id          UUID REFERENCES agent_job(id) ON DELETE SET NULL,
    prompt_version_id UUID REFERENCES prompt_version(id) ON DELETE SET NULL,
    eval_run_id     TEXT NOT NULL,          -- groups all results from one harness run
    eval_case_id    TEXT NOT NULL,          -- golden dataset case identifier
    agent_name      TEXT NOT NULL,

    -- Scores (all 0.0–1.0)
    cause_correct   BOOLEAN,               -- root cause matched expected
    patch_score     FLOAT,                 -- LLM-as-judge patch quality score
    attempts        INT,
    tokens_used     INT,
    judge_output    JSONB,                 -- full LLM-as-judge response

    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_eval_result_run
    ON eval_result (eval_run_id, agent_name, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_eval_result_prompt
    ON eval_result (prompt_version_id, patch_score);

-- ============================================================
-- HUMAN FEEDBACK
-- Signal from humans about the quality of agent output
-- Drives the feedback loop / RLHF-lite memory reinforcement
-- ============================================================
CREATE TABLE IF NOT EXISTS human_feedback (
    id          UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    job_id      UUID NOT NULL REFERENCES agent_job(id) ON DELETE CASCADE,
    tenant_id   UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,

    outcome     TEXT NOT NULL,
    -- PR_MERGED          → strong positive signal
    -- PR_CLOSED          → negative signal
    -- COMMIT_REVERTED    → strong negative signal (fix was wrong)
    -- REVIEW_APPROVED    → strong positive (reviewer accepted without changes)
    -- ESCALATION_RESOLVED → human solved the escalated issue

    reviewer    TEXT,                       -- GitHub username of the human
    notes       TEXT,                       -- optional human-written note
    pr_number   INT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_feedback_job
    ON human_feedback (job_id);
CREATE INDEX IF NOT EXISTS idx_feedback_tenant_outcome
    ON human_feedback (tenant_id, outcome, created_at DESC);

-- ============================================================
-- REPO PROCEDURES (Procedural Memory — Structured)
-- Cached knowledge about how to work with each repo
-- ============================================================
CREATE TABLE IF NOT EXISTS repo_procedure (
    id                  UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    tenant_id           UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    repo                TEXT NOT NULL,

    test_framework      TEXT,               -- MAVEN | GRADLE | PYTEST | NPM_JEST | CARGO | GO_TEST
    test_command        TEXT,               -- exact command to run tests
    lint_command        TEXT,
    setup_commands      TEXT[],             -- ordered list of setup steps
    required_env_vars   TEXT[],
    known_flaky_tests   TEXT[],             -- regex patterns to exclude from failure analysis
    coverage_command    TEXT,               -- command to run with coverage measurement
    coverage_report_path TEXT,              -- where coverage output is written

    -- Auto-detected or manually set
    detection_source    TEXT DEFAULT 'auto',  -- 'auto' | 'manual'
    last_successful_run TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    UNIQUE (tenant_id, repo)
);

COMMENT ON TABLE repo_procedure IS 'Procedural memory for each repo. Test commands, setup steps, known flaky tests. Updated each time Test Runner successfully runs.';

-- ============================================================
-- STATE TRANSITION LOG
-- History of every state machine transition for each job
-- ============================================================
CREATE TABLE IF NOT EXISTS state_transition (
    id          BIGSERIAL PRIMARY KEY,
    job_id      UUID NOT NULL REFERENCES agent_job(id) ON DELETE CASCADE,
    from_state  TEXT,
    to_state    TEXT NOT NULL,
    trigger     TEXT,                       -- which Kafka event caused this transition
    metadata    JSONB DEFAULT '{}',
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_state_transition_job
    ON state_transition (job_id, created_at ASC);

-- ============================================================
-- HELPER FUNCTIONS
-- ============================================================

-- Auto-update updated_at on any UPDATE
CREATE OR REPLACE FUNCTION update_updated_at_column()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_tenants_updated_at
    BEFORE UPDATE ON tenants
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER trg_agent_job_updated_at
    BEFORE UPDATE ON agent_job
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER trg_agent_memory_updated_at
    BEFORE UPDATE ON agent_memory
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER trg_repo_procedure_updated_at
    BEFORE UPDATE ON repo_procedure
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

-- ============================================================
-- SEED DATA — Default prompts (version 1, inactive until activated)
-- ============================================================
INSERT INTO prompt_version (agent_name, prompt_key, version, content, changelog, is_active, created_by)
VALUES
    ('investigator', 'system', 1,
     'You are a software forensics expert with deep knowledge of git history analysis and causal inference. Given a directed acyclic graph of commits and their relationships, identify the root cause of a bug. Reason step by step. Explain the causal chain in plain English, starting from the original bad commit through to the observable failure. Be precise and cite specific commit SHAs and file names.',
     'Initial version', true, 'system'),

    ('fixer', 'system', 1,
     'You are a senior software engineer performing an autonomous code repair. You will receive a broken function, its causal root cause analysis, test failure output, and any relevant past fixes on this repository. First REASON carefully about what the root cause means for the code. Then ACT by writing the corrected code. Be surgical — only change what is necessary. Output only valid, compilable code.',
     'Initial version', true, 'system'),

    ('reviewer', 'system', 1,
     'You are the AI engineer who autonomously wrote and committed a patch to fix a production bug. You have full context: the original bug, the causal analysis showing why it happened, your patch, and the test results confirming it works. When a human reviewer posts a comment, respond thoughtfully and with technical depth. Defend your decision if the reviewer is wrong. Acknowledge issues if the reviewer raises a valid concern — and if they do, indicate that you will generate a new fix.',
     'Initial version', true, 'system'),

    ('planner', 'system', 1,
     'You are a software architect classifying bugs and planning fix strategies. Given a bug description, stack trace, and causal chain, classify the bug type and select the most appropriate fix strategy. Be precise about which files and functions need to change. Constrain the fix scope — a good patch touches as few files as possible.',
     'Initial version', true, 'system'),

    ('planner', 'classify_user', 1,
     'Bug type: {error_type}\nStack trace:\n{stack_trace}\n\nCausal chain:\n{causal_chain}\n\nBroken function:\n{broken_fn}\n\nClassify this bug and produce a fix strategy. Respond with a JSON object matching the PlannerOutput schema.',
     'Initial version', true, 'system')
ON CONFLICT DO NOTHING;

-- ============================================================
-- SEED DATA — Default tenant for local development
-- ============================================================
INSERT INTO tenants (org_name, config)
VALUES ('local-dev', '{"environment": "development"}')
ON CONFLICT (org_name) DO NOTHING;

-- ============================================================
-- VIEWS (useful for Dashboard UI and monitoring)
-- ============================================================

-- Job summary by state (for pipeline health dashboard)
CREATE OR REPLACE VIEW v_job_state_counts AS
SELECT
    tenant_id,
    state,
    COUNT(*) AS count,
    AVG(EXTRACT(EPOCH FROM (updated_at - created_at))) AS avg_duration_seconds
FROM agent_job
WHERE created_at >= NOW() - INTERVAL '24 hours'
GROUP BY tenant_id, state;

-- Recent escalations with reason
CREATE OR REPLACE VIEW v_recent_escalations AS
SELECT
    aj.id,
    aj.tenant_id,
    aj.repo,
    aj.error_type,
    aj.escalation_reason,
    aj.attempts,
    aj.token_budget_used,
    aj.created_at
FROM agent_job aj
WHERE aj.state = 'ESCALATED'
ORDER BY aj.created_at DESC;

-- Token budget utilisation per tenant
CREATE OR REPLACE VIEW v_token_budget_usage AS
SELECT
    tenant_id,
    COUNT(*) AS active_jobs,
    SUM(token_budget_used) AS total_tokens_used,
    AVG(CAST(token_budget_used AS FLOAT) / token_budget_limit) AS avg_budget_utilisation
FROM agent_job
WHERE state NOT IN ('DONE', 'CLOSED', 'ESCALATED')
GROUP BY tenant_id;

-- Prompt version performance comparison
CREATE OR REPLACE VIEW v_prompt_performance AS
SELECT
    pv.agent_name,
    pv.prompt_key,
    pv.version,
    pv.is_active,
    pv.eval_score,
    pv.eval_runs,
    COUNT(al.id) AS total_llm_calls,
    AVG(al.latency_ms) AS avg_latency_ms,
    SUM(al.tokens_used) AS total_tokens
FROM prompt_version pv
LEFT JOIN audit_log al ON al.prompt_version_id = pv.id
GROUP BY pv.id
ORDER BY pv.agent_name, pv.prompt_key, pv.version;

-- File risk scores (which files have introduced the most bugs)
CREATE OR REPLACE VIEW v_file_risk AS
SELECT
    tenant_id,
    repo,
    affected_file,
    COUNT(*) AS bug_count,
    AVG(causal_score) AS avg_causal_score,
    MAX(created_at) AS last_bug_at
FROM agent_job
WHERE affected_file IS NOT NULL
GROUP BY tenant_id, repo, affected_file
ORDER BY bug_count DESC;
