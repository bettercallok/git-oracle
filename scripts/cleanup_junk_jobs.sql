-- Removes the junk jobs that unhandled GitHub webhook events used to create
-- (repo 'unknown/repo'; fixed in 2026687). They count against an
-- organisation's monthly job limit until they are gone.
--
-- This DELETES rows. Run the SELECT first and check the numbers:
--
--   docker exec -i <postgres container> psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" < scripts/cleanup_junk_jobs.sql
--
-- It runs in one transaction and ends with ROLLBACK, so as written it changes
-- nothing. Change the last line to COMMIT once the counts look right.

BEGIN;

SELECT tenant_id, state, COUNT(*) AS jobs
FROM agent_job
WHERE repo = 'unknown/repo'
GROUP BY tenant_id, state
ORDER BY tenant_id, state;

CREATE TEMP TABLE junk_jobs ON COMMIT DROP AS
    SELECT id FROM agent_job WHERE repo = 'unknown/repo';

-- Rows that point at a job. A table that does not exist in this database
-- (an older schema) is skipped rather than failing the whole run.
DO $$
DECLARE
    child record;
BEGIN
    FOR child IN
        SELECT * FROM (VALUES
            ('job_event', 'job_id'),
            ('escalations', 'job_id'),
            ('pr_outcomes', 'job_id'),
            ('job_prompt_versions', 'job_id')
        ) AS t(table_name, column_name)
    LOOP
        IF EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_name = child.table_name AND column_name = child.column_name) THEN
            EXECUTE format('DELETE FROM %I WHERE %I IN (SELECT id FROM junk_jobs)',
                           child.table_name, child.column_name);
        ELSE
            RAISE NOTICE 'skipping %.% (not in this database)', child.table_name, child.column_name;
        END IF;
    END LOOP;
END $$;

DELETE FROM agent_job WHERE id IN (SELECT id FROM junk_jobs);

SELECT COUNT(*) AS junk_jobs_left FROM agent_job WHERE repo = 'unknown/repo';

ROLLBACK;   -- change to COMMIT to keep the deletion
