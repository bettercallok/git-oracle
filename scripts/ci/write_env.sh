#!/usr/bin/env bash
# Writes .env for a CI run of the full stack: .env.example plus throwaway
# per-run secrets for everything internal. The LLM settings come from the
# caller's LLM_URL / LLM_KEY / LLM_MODEL. Shared by eval.yml (real LLM) and
# ci.yml's pipeline-e2e job (mock LLM).
#
# Lines are appended, so they override .env.example's values when sourced.
# Exports GITORACLE_API_KEY to later steps via $GITHUB_ENV (masked).
set -euo pipefail

: "${LLM_URL:?LLM_URL is required}"
: "${LLM_MODEL:?LLM_MODEL is required}"

rnd() { openssl rand -hex 24; }
API_KEY=$(rnd)
echo "::add-mask::$API_KEY"

cp .env.example .env
{
  echo ""
  echo "# ── CI overrides ──"
  echo "GITORACLE_API_KEY=$API_KEY"
  echo "GITORACLE_LEGACY_API_KEY_ENABLED=true"
  echo "GITORACLE_INTERNAL_TOKEN=$(rnd)"
  echo "POSTGRES_PASSWORD=$(rnd)"
  echo "REDIS_PASSWORD=$(rnd)"
  echo "QDRANT_API_KEY=$(rnd)"
  echo "GRAFANA_ADMIN_PASSWORD=$(rnd)"
  echo "LANGFUSE_NEXTAUTH_SECRET=$(rnd)"
  echo "LANGFUSE_SALT=$(rnd)"
  # Langfuse isn't started in CI; empty keys turn tracing off instead of
  # failing (and blocking on a flush) on every LLM call.
  echo "LANGFUSE_PUBLIC_KEY="
  echo "LANGFUSE_SECRET_KEY="
  echo "JWT_SECRET=$(rnd)$(rnd)"
  echo "LLM_BASE_URL=$LLM_URL"
  echo "OPENAI_API_KEY=${LLM_KEY:-unused}"
  echo "LLM_MODEL_NAME=$LLM_MODEL"
  # Optional: agent memory's embeddings endpoint (memory is off without it).
  echo "EMBEDDINGS_BASE_URL=${EMBEDDINGS_URL:-}"
} >> .env

if [ -n "${GITHUB_ENV:-}" ]; then
  echo "GITORACLE_API_KEY=$API_KEY" >> "$GITHUB_ENV"
fi
