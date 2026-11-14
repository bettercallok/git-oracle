"""Regression tests for the medium findings of the codebase review."""
import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))
sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "agents", "guardrails"))

from shared import limits, structured_output, tenancy  # noqa: E402


# ─── Tenant id travels with the job ──────────────────────────────────────────

def test_tenant_id_is_taken_from_the_payload():
    tenant = "3f2b8c1e-9d4a-4b7e-8a10-5c6d7e8f9a0b"
    assert tenancy.tenant_id_of({"tenant_id": tenant}) == tenant
    assert tenancy.tenant_id_of({"tenant_id": f"  {tenant.upper()} "}) == tenant


def test_missing_or_malformed_tenant_id_falls_back_to_the_default():
    for payload in ({}, {"tenant_id": ""}, {"tenant_id": None}, {"tenant_id": "not-a-uuid"},
                    {"tenant_id": 7}, None, []):
        assert tenancy.tenant_id_of(payload) == tenancy.DEFAULT_TENANT_ID


def test_agents_no_longer_hardcode_the_default_tenant():
    agents = os.path.join(os.path.dirname(__file__), "..", "agents")
    for agent in ("investigator", "planner", "fixer"):
        with open(os.path.join(agents, agent, "main.py")) as f:
            source = f.read()
        assert 'tenant_id="00000000-0000-0000-0000-000000000000"' not in source, agent
        assert "tenant_id_of(payload)" in source, agent


# ─── One patch-size limit for the fixer and guardrails ───────────────────────

def test_guardrails_and_fixer_share_the_patch_line_limit():
    import patch_scanner
    assert patch_scanner.SCOPE_LIMIT == limits.PATCH_LINE_LIMIT


def test_count_changed_lines_ignores_file_headers_and_context():
    diff = "--- a/f.py\n+++ b/f.py\n@@ -1,3 +1,3 @@\n context\n-old\n+new\n+added\n"
    assert limits.count_changed_lines(diff) == 3
    assert limits.count_changed_lines("") == 0


# ─── The fallback LLM gets its own key, never the primary's ──────────────────

def test_fallback_headers_never_carry_the_primary_key(monkeypatch):
    monkeypatch.setenv("OPENAI_API_KEY", "sk-primary")
    monkeypatch.delenv("LLM_FALLBACK_API_KEY", raising=False)
    assert structured_output.fallback_headers() == {}

    monkeypatch.setenv("LLM_FALLBACK_API_KEY", "fb-key")
    assert structured_output.fallback_headers() == {"Authorization": "Bearer fb-key"}


# ─── The fixer shows more than the first 4,000 characters ────────────────────

def test_source_context_is_not_cut_at_4000_characters():
    from agents.fixer import main as fixer
    source = "x = 1\n" * 2000          # 12,000 characters
    assert fixer.clip_source_context(source) == source
    clipped = fixer.clip_source_context(source, limit=100)
    assert clipped.startswith(source[:100]) and "truncated" in clipped
    with open(fixer.__file__) as f:
        assert "# Trimmed for safety" not in f.read()
