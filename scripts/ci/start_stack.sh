#!/usr/bin/env bash
# Starts what the pipeline needs in CI and waits until it's ready:
#   infra (only postgres, neo4j, redis, qdrant, kafka — observability would just
#   eat runner memory) → the 6 Java services + 7 agents via start_local.sh →
#   readiness by log line, not by curling ports (see CLAUDE.md).
# Expects .env (scripts/ci/write_env.sh) and ai_core/.venv.
set -euo pipefail

COMPOSE="docker compose -f docker-compose.infra.yml"
$COMPOSE up -d --wait postgres neo4j redis qdrant kafka
$COMPOSE up --no-deps --exit-code-from neo4j-init neo4j-init
$COMPOSE up --no-deps --exit-code-from kafka-init kafka-init

SKIP_DASHBOARD=1 ./start_local.sh

deadline=$((SECONDS + ${READY_TIMEOUT_SECONDS:-300}))
wait_for() { # <log file> <pattern> <name>
  until grep -q "$2" "$1" 2>/dev/null; do
    if [ $SECONDS -gt $deadline ]; then
      echo "::error::$3 did not start in time"; tail -50 "$1" || true; exit 1
    fi
    sleep 3
  done
  echo "✅ $3"
}
for svc in api-gateway error-ingestor orchestrator test-runner github-bot git-forensics; do
  wait_for "java-backend/$svc.log" "Started .*Application" "$svc"
done
for agent in fixer planner investigator reviewer prompt_registry guardrails commit_analyst; do
  wait_for "ai_core/$agent.log" "Uvicorn running" "$agent"
done
