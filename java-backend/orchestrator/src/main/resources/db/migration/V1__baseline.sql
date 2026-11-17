-- Baseline: the schema Hibernate's ddl-auto: update built from the entities in
-- git-oracle-core, as of the switch to Flyway (generated on an empty database,
-- then checked against an existing one: same columns, same constraints).
--
-- IF NOT EXISTS throughout, so this is a no-op on a database ddl-auto already
-- built and the real schema on a new one. agent_memory and prompt_version stay
-- in infrastructure/postgres/init.sql: the Python agents own them.
--
-- Change the schema with a new V<n>__<what>.sql file, never by editing this one.

CREATE TABLE IF NOT EXISTS public.agent_job (

    id uuid NOT NULL,
    agent_version character varying(255),
    attempts integer,
    authorized_files text,
    base_commit character varying(255),
    causal_score double precision,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP,
    error_id character varying(255) NOT NULL,
    escalation_report jsonb,
    fix_patch text,
    fix_strategy character varying(255),
    investigation_result jsonb,
    pr_url character varying(255),
    quality_score double precision,
    raw_payload jsonb,
    repo character varying(255) NOT NULL,
    root_commit character varying(255),
    state character varying(255) NOT NULL,
    state_changed_at timestamp(6) with time zone,
    state_reason text,
    tenant_id uuid NOT NULL,
    token_budget_limit integer,
    token_budget_used integer,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT agent_job_pkey PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS public.api_keys (

    id uuid NOT NULL,
    created_at timestamp(6) with time zone,
    created_by_user_id uuid,
    expires_at timestamp(6) with time zone,
    key_hash character varying(128) NOT NULL,
    key_prefix character varying(32) NOT NULL,
    last_used_at timestamp(6) with time zone,
    name character varying(255),
    revoked_at timestamp(6) with time zone,
    scopes text,
    tenant_id uuid NOT NULL,
    CONSTRAINT api_keys_pkey PRIMARY KEY (id),
    CONSTRAINT idx_api_keys_prefix UNIQUE (key_prefix)
);

CREATE TABLE IF NOT EXISTS public.audit_log (

    id uuid NOT NULL,
    action character varying(64) NOT NULL,
    actor_user_id uuid,
    created_at timestamp(6) with time zone NOT NULL,
    details text,
    target character varying(255),
    tenant_id uuid NOT NULL,
    CONSTRAINT audit_log_pkey PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS public.escalations (

    id uuid NOT NULL,
    confidence_score double precision,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP,
    reason text,
    resolved_at timestamp(6) with time zone,
    status character varying(255),
    job_id uuid,
    CONSTRAINT escalations_pkey PRIMARY KEY (id),
    CONSTRAINT fk9ywn3xs8gsy5xa19t3tfv8v3g FOREIGN KEY (job_id) REFERENCES public.agent_job(id)
);

CREATE TABLE IF NOT EXISTS public.eval_runs (

    id uuid NOT NULL,
    accuracy double precision,
    avg_latency_ms integer,
    cases_total integer,
    created_at timestamp(6) with time zone,
    golden_dataset_version character varying(255),
    tenant_id uuid,
    CONSTRAINT eval_runs_pkey PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS public.github_connect_requests (

    id uuid NOT NULL,
    completed_at timestamp(6) with time zone,
    expires_at timestamp(6) with time zone NOT NULL,
    installation_id bigint,
    state_hash character varying(128) NOT NULL,
    tenant_id uuid NOT NULL,
    user_id uuid NOT NULL,
    CONSTRAINT github_connect_requests_pkey PRIMARY KEY (id),
    CONSTRAINT idx_github_connect_state UNIQUE (state_hash)
);

CREATE TABLE IF NOT EXISTS public.job_event (

    id uuid NOT NULL,
    actor character varying(64) NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    details jsonb,
    from_state character varying(32),
    job_id uuid NOT NULL,
    message text,
    tenant_id uuid,
    to_state character varying(32),
    type character varying(32) NOT NULL,
    CONSTRAINT job_event_type_check CHECK (((type)::text = ANY ((ARRAY['STATE_CHANGE'::character varying, 'AGENT_PROGRESS'::character varying, 'ERROR'::character varying])::text[]))),
    CONSTRAINT job_event_pkey PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS public.job_prompt_versions (

    id uuid NOT NULL,
    agent_name character varying(64) NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP,
    job_id uuid NOT NULL,
    prompt_version integer NOT NULL,
    tokens_used integer,
    CONSTRAINT job_prompt_versions_pkey PRIMARY KEY (id),
    CONSTRAINT uk2ar90rw3b0g7oy9sa01335e1c UNIQUE (job_id, agent_name)
);

CREATE TABLE IF NOT EXISTS public.organization_invitations (

    id uuid NOT NULL,
    accepted_at timestamp(6) with time zone,
    created_at timestamp(6) with time zone NOT NULL,
    expires_at timestamp(6) with time zone NOT NULL,
    github_id bigint NOT NULL,
    github_login character varying(255) NOT NULL,
    invited_by_user_id uuid,
    revoked_at timestamp(6) with time zone,
    role character varying(16) NOT NULL,
    tenant_id uuid NOT NULL,
    CONSTRAINT organization_invitations_role_check CHECK (((role)::text = ANY ((ARRAY['VIEWER'::character varying, 'MEMBER'::character varying, 'ADMIN'::character varying, 'OWNER'::character varying])::text[]))),
    CONSTRAINT organization_invitations_pkey PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS public.organization_members (

    id uuid NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    invited_by_user_id uuid,
    role character varying(16) NOT NULL,
    tenant_id uuid NOT NULL,
    user_id uuid NOT NULL,
    CONSTRAINT organization_members_role_check CHECK (((role)::text = ANY ((ARRAY['VIEWER'::character varying, 'MEMBER'::character varying, 'ADMIN'::character varying, 'OWNER'::character varying])::text[]))),
    CONSTRAINT organization_members_pkey PRIMARY KEY (id),
    CONSTRAINT uq_org_member UNIQUE (tenant_id, user_id)
);

CREATE TABLE IF NOT EXISTS public.pr_outcomes (

    id uuid NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP,
    job_id uuid NOT NULL,
    outcome character varying(32) NOT NULL,
    pr_url character varying(255),
    reviewer character varying(255),
    CONSTRAINT pr_outcomes_pkey PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS public.tenant_allowed_repos (

    id uuid NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    repo character varying(255) NOT NULL,
    tenant_id uuid NOT NULL,
    CONSTRAINT tenant_allowed_repos_pkey PRIMARY KEY (id),
    CONSTRAINT uk_tenant_allowed_repo UNIQUE (tenant_id, repo)
);

CREATE TABLE IF NOT EXISTS public.tenants (

    id uuid NOT NULL,
    config jsonb,
    created_at timestamp(6) with time zone,
    github_app_installation_id character varying(255),
    monthly_job_limit integer,
    monthly_token_limit bigint,
    org_name character varying(255) NOT NULL,
    sentry_webhook_secret character varying(255),
    CONSTRAINT tenants_pkey PRIMARY KEY (id),
    CONSTRAINT uk_7jjknlbbderiv3oxbaw17642b UNIQUE (org_name)
);

CREATE TABLE IF NOT EXISTS public.user_sessions (

    id uuid NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    expires_at timestamp(6) with time zone NOT NULL,
    revoked_at timestamp(6) with time zone,
    scopes text,
    tenant_id uuid NOT NULL,
    token_hash character varying(128) NOT NULL,
    user_id uuid NOT NULL,
    CONSTRAINT idx_user_sessions_token_hash UNIQUE (token_hash),
    CONSTRAINT user_sessions_pkey PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS public.users (

    id uuid NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    github_id bigint NOT NULL,
    github_login character varying(255) NOT NULL,
    last_login_at timestamp(6) with time zone,
    tenant_id uuid NOT NULL,
    CONSTRAINT idx_users_github_id UNIQUE (github_id),
    CONSTRAINT users_pkey PRIMARY KEY (id)
);

CREATE INDEX IF NOT EXISTS idx_api_keys_tenant ON public.api_keys USING btree (tenant_id);
CREATE INDEX IF NOT EXISTS idx_audit_tenant_time ON public.audit_log USING btree (tenant_id, created_at);
CREATE INDEX IF NOT EXISTS idx_job_event_job ON public.job_event USING btree (job_id, created_at);
CREATE INDEX IF NOT EXISTS idx_org_invitations_github ON public.organization_invitations USING btree (github_id);
CREATE INDEX IF NOT EXISTS idx_org_invitations_tenant ON public.organization_invitations USING btree (tenant_id);
CREATE INDEX IF NOT EXISTS idx_org_members_user ON public.organization_members USING btree (user_id);
CREATE INDEX IF NOT EXISTS idx_user_sessions_user ON public.user_sessions USING btree (user_id);
