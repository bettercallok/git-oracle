#!/bin/sh
# Starts the agent named by $AGENT, the same way start_local.sh does.
set -eu
case "${AGENT:-}" in
  investigator|fixer|reviewer|prompt_registry|guardrails|planner)
    exec python "agents/${AGENT}/main.py" ;;
  commit_analyst)
    exec python -m agents.commit_analyst.main ;;
  *)
    echo "AGENT must be one of: investigator fixer reviewer commit_analyst prompt_registry guardrails planner (got '${AGENT:-}')" >&2
    exit 64 ;;
esac
