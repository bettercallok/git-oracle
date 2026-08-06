-- GitOracle Postgres Initial Schema

-- Enable vector extension
CREATE EXTENSION IF NOT EXISTS vector;

-- Create Langfuse Database
CREATE DATABASE langfuse;

-- Connect to default database (gitOracle)
\c gitOracle

-- NOTE: agent_job, escalations, eval_runs, and tenants are owned by the Java
-- JPA entities (Hibernate ddl-auto=update creates them with correct columns and
-- foreign keys). They are intentionally NOT created here — a prior version of this
-- file pre-created an `agent_jobs` (plural) table plus an `escalations` table whose
-- FK pointed at it, which conflicted with the JPA `agent_job` table and made every
-- escalation insert fail. Let Hibernate own those tables.

-- Table: agent_memory (episodic/semantic memory using pgvector)
-- Columns match ai_core/shared/memory.py (INSERT/SELECT via asyncpg).
CREATE TABLE agent_memory (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,
    repo VARCHAR(255) NOT NULL,
    memory_type VARCHAR(50) NOT NULL,
    content TEXT NOT NULL,
    embedding VECTOR(384),          -- all-minilm embedding dimension
    metadata JSONB DEFAULT '{}',
    confidence FLOAT DEFAULT 1.0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Vector similarity index + lookup index for the WHERE clause
CREATE INDEX ON agent_memory USING hnsw (embedding vector_cosine_ops);
CREATE INDEX ON agent_memory (tenant_id, repo, memory_type);

-- Table: prompt_version (dynamic, versioned prompt registry served by agents/prompt_registry)
CREATE TABLE prompt_version (
    id SERIAL PRIMARY KEY,
    agent_name VARCHAR(100) NOT NULL,
    prompt_key VARCHAR(100) NOT NULL,
    version INTEGER NOT NULL,
    content TEXT NOT NULL,
    is_active BOOLEAN DEFAULT false,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (agent_name, prompt_key, version)
);

CREATE INDEX ON prompt_version (agent_name, prompt_key, is_active);

-- Seed default active prompts for each agent
INSERT INTO prompt_version (agent_name, prompt_key, version, content, is_active) VALUES (
    'planner', 'system', 1,
    'You are the GitOracle Planner Agent. An Investigator Agent has found the likely root cause of a bug. Your job is to generate a strict, constrained action plan for the Fixer Agent to execute. Generate a strict plan. You must pick exactly one FixStrategy. Be very specific about which functions need to change. Constrain the Fixer Agent by setting max_lines_to_change to the absolute minimum needed.',
    true
);

INSERT INTO prompt_version (agent_name, prompt_key, version, content, is_active) VALUES (
    'fixer', 'system', 1,
    'You are the GitOracle Fixer Agent. A Planner Agent has given you a strict blueprint to fix a bug. You MUST write a patch (unified diff) that fixes the bug according to the plan.',
    true
);

INSERT INTO prompt_version (agent_name, prompt_key, version, content, is_active) VALUES (
    'investigator', 'system', 1,
    'You are the GitOracle Investigator Agent. A bug has been reported. Analyze the git history and determine which commits most likely introduced the bug. Rank them and provide a causal_effect_score (0.0 to 1.0) and reasoning for each.',
    true
);
