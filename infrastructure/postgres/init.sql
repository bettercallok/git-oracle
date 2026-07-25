-- GitOracle Postgres Initial Schema

-- Enable vector extension
CREATE EXTENSION IF NOT EXISTS vector;

-- Create Langfuse Database
CREATE DATABASE langfuse;

-- Connect to default database (gitOracle)
\c gitOracle

-- Table: agent_jobs
CREATE TABLE agent_jobs (
    id UUID PRIMARY KEY,
    repo_url VARCHAR(255) NOT NULL,
    commit_hash VARCHAR(40) NOT NULL,
    status VARCHAR(50) NOT NULL,
    error_message TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    tokens_used INTEGER DEFAULT 0
);

-- Table: escalations
CREATE TABLE escalations (
    id UUID PRIMARY KEY,
    job_id UUID REFERENCES agent_jobs(id),
    reason VARCHAR(255) NOT NULL,
    confidence_score FLOAT,
    status VARCHAR(50) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    resolved_at TIMESTAMP
);

-- Table: eval_runs
CREATE TABLE eval_runs (
    id UUID PRIMARY KEY,
    golden_dataset_version VARCHAR(50) NOT NULL,
    accuracy FLOAT NOT NULL,
    avg_latency_ms INTEGER NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Table: memory_store (Semantic memory using pgvector)
CREATE TABLE memory_store (
    id UUID PRIMARY KEY,
    repo_url VARCHAR(255) NOT NULL,
    content TEXT NOT NULL,
    metadata JSONB,
    embedding VECTOR(1536) -- Assuming 1536 dimensions for embeddings
);

-- Create index for vector similarity search
CREATE INDEX ON memory_store USING hnsw (embedding vector_cosine_ops);
