// ============================================================
// GitOracle — Neo4j Schema Initialisation
// Run via: cypher-shell -u neo4j -p <password> -f init.cypher
// ============================================================

// ─── Constraints (uniqueness + existence) ─────────────────────

// Commit: SHA is globally unique
CREATE CONSTRAINT constraint_commit_sha IF NOT EXISTS
FOR (c:Commit) REQUIRE c.sha IS UNIQUE;

// Bug: fingerprint is unique (error fingerprint from Sentry)
CREATE CONSTRAINT constraint_bug_fingerprint IF NOT EXISTS
FOR (b:Bug) REQUIRE b.fingerprint IS UNIQUE;

// Developer: email is unique
CREATE CONSTRAINT constraint_developer_email IF NOT EXISTS
FOR (d:Developer) REQUIRE d.email IS UNIQUE;

// Fix: one fix record per agent job
CREATE CONSTRAINT constraint_fix_job_id IF NOT EXISTS
FOR (f:Fix) REQUIRE f.job_id IS UNIQUE;

// PullRequest: unique by repo + number
CREATE CONSTRAINT constraint_pr_url IF NOT EXISTS
FOR (p:PullRequest) REQUIRE p.url IS UNIQUE;

// AgentJob: unique by ID
CREATE CONSTRAINT constraint_agent_job_id IF NOT EXISTS
FOR (j:AgentJob) REQUIRE j.id IS UNIQUE;

// File: unique by repo + path
CREATE CONSTRAINT constraint_file_unique IF NOT EXISTS
FOR (f:File) REQUIRE (f.path, f.repo) IS UNIQUE;

// EscalationReport: unique per job
CREATE CONSTRAINT constraint_escalation_job IF NOT EXISTS
FOR (e:EscalationReport) REQUIRE e.job_id IS UNIQUE;

// ─── Indexes (performance) ────────────────────────────────────

// Commit lookups by repo and timestamp
CREATE INDEX index_commit_repo IF NOT EXISTS
FOR (c:Commit) ON (c.repo);

CREATE INDEX index_commit_timestamp IF NOT EXISTS
FOR (c:Commit) ON (c.timestamp);

CREATE INDEX index_commit_author IF NOT EXISTS
FOR (c:Commit) ON (c.author_email);

// File risk score queries
CREATE INDEX index_file_risk IF NOT EXISTS
FOR (f:File) ON (f.risk_score);

CREATE INDEX index_file_language IF NOT EXISTS
FOR (f:File) ON (f.language);

// Bug queries by first_seen (time-range forensics)
CREATE INDEX index_bug_first_seen IF NOT EXISTS
FOR (b:Bug) ON (b.first_seen);

CREATE INDEX index_bug_error_type IF NOT EXISTS
FOR (b:Bug) ON (b.error_type);

// Developer risk score queries (for heatmap)
CREATE INDEX index_developer_risk IF NOT EXISTS
FOR (d:Developer) ON (d.bug_count);

// AgentJob state queries
CREATE INDEX index_agent_job_state IF NOT EXISTS
FOR (j:AgentJob) ON (j.state);

CREATE INDEX index_agent_job_tenant IF NOT EXISTS
FOR (j:AgentJob) ON (j.tenant_id);

// ─── Node property documentation ─────────────────────────────
//
// (:Commit {
//     sha:           String  — 40-char git SHA (unique)
//     short_sha:     String  — first 7 chars
//     message:       String  — full commit message
//     message_short: String  — first line only
//     author_name:   String
//     author_email:  String
//     timestamp:     DateTime
//     repo:          String  — "org/repo" format
//     files_changed: [String] — list of file paths
//     additions:     Integer
//     deletions:     Integer
//     is_merge:      Boolean
//     pr_number:     Integer  — linked PR if any
// })
//
// (:File {
//     path:        String  — repo-relative path
//     repo:        String  — "org/repo" format
//     language:    String  — Java | Python | JavaScript | etc.
//     risk_score:  Float   — historical bug rate (higher = riskier)
//     bug_touches: Integer — number of bug-introducing commits that touched this file
//     last_modified: DateTime
// })
//
// (:Bug {
//     fingerprint:   String  — unique error fingerprint (from Sentry)
//     error_type:    String  — NullPointerException | etc.
//     error_message: String
//     stack_trace:   String
//     first_seen:    DateTime
//     last_seen:     DateTime
//     occurrence_count: Integer
//     severity:      String  — fatal | error | warning
//     environment:   String  — production | staging
// })
//
// (:Developer {
//     name:            String
//     email:           String  — unique
//     github_username: String
//     bug_count:       Integer — total bugs introduced (updated by analytics)
//     fix_count:       Integer — total bugs fixed
//     risk_score:      Float   — bug_count / total_commits ratio
// })
//
// (:Fix {
//     job_id:       String  — UUID from agent_job table
//     patch:        String  — unified diff
//     strategy:     String  — FixStrategy enum
//     attempts:     Integer
//     quality_score: Float
//     generated_at: DateTime
// })
//
// (:PullRequest {
//     number:   Integer
//     url:      String  — unique
//     title:    String
//     state:    String  — open | closed | merged
//     repo:     String
//     created_at: DateTime
//     merged_at:  DateTime
// })
//
// (:AgentJob {
//     id:         String  — UUID (unique)
//     tenant_id:  String
//     state:      String  — mirrors PostgreSQL state
//     repo:       String
//     created_at: DateTime
// })
//
// (:EscalationReport {
//     job_id:          String
//     summary:         String  — LLM-generated summary
//     blocking_tests:  [String]
//     best_patch:      String
//     hypothesis:      String  — agent's best guess
//     created_at:      DateTime
// })

// ─── Relationship documentation ───────────────────────────────
//
// (:Commit)-[:MODIFIED]->(:File)
// (:Commit)-[:AUTHORED_BY]->(:Developer)
// (:Commit)-[:PARENT_OF]->(:Commit)           — git DAG (parent commits)
// (:Commit)-[:INTRODUCED]->(:Bug)             — causal attribution
// (:Commit)-[:SIMILAR_TO { cosine_sim }]->(:Commit)  — semantic similarity
// (:File)-[:CO_CHANGED_WITH { frequency }]->(:File)  — files often changed together
// (:Bug)-[:CAUSED { strength: float }]->(:Bug)        — causal chain edges
// (:Bug)-[:PREVIOUSLY_SEEN_IN]->(:Repo)               — cross-repo tracking
// (:Fix)-[:RESOLVES]->(:Bug)
// (:Fix)-[:GENERATED_BY]->(:AgentJob)
// (:PullRequest)-[:CONTAINS]->(:Fix)
// (:AgentJob)-[:INVESTIGATED]->(:Bug)
// (:AgentJob)-[:PRODUCED_ESCALATION]->(:EscalationReport)
// (:Developer)-[:HAS_HIGH_RISK_FILE]->(:File)         — analytics relationship

// ─── Sample useful queries ────────────────────────────────────

// Q1: Find all ancestors of a commit (git history walk, depth 10)
// MATCH (c:Commit {sha: $sha})-[:PARENT_OF*1..10]->(ancestor:Commit)
// RETURN ancestor ORDER BY ancestor.timestamp DESC

// Q2: Causal subgraph — all bugs traceable to a commit
// MATCH (c:Commit {sha: $sha})-[:INTRODUCED]->(b:Bug)-[:CAUSED*0..5]->(downstream:Bug)
// RETURN c, b, downstream

// Q3: High-risk developer report
// MATCH (d:Developer)-[:AUTHORED]->(c:Commit)-[:INTRODUCED]->(b:Bug)
// WITH d, count(b) as bugs ORDER BY bugs DESC LIMIT 10
// RETURN d.name, d.email, bugs

// Q4: File risk heatmap
// MATCH (c:Commit)-[:INTRODUCED]->(:Bug), (c)-[:MODIFIED]->(f:File)
// WITH f, count(*) as bug_touches ORDER BY bug_touches DESC
// RETURN f.path, f.repo, bug_touches LIMIT 20

// Q5: Causal chain strength between two bugs
// MATCH path = (b1:Bug {fingerprint: $fp1})-[:CAUSED*]->(b2:Bug {fingerprint: $fp2})
// RETURN path, reduce(strength=1.0, r IN relationships(path) | strength * r.strength) AS chain_strength
