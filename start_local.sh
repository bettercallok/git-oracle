#!/bin/bash

# Ensure .env exists
if [ ! -f .env ]; then
  echo "Creating .env from .env.example..."
  make env-setup
fi

# ─── Clear zombie processes on all service ports ───────────────
# IMPORTANT: Only kill Java/Python/Node processes — never Docker processes.
# Docker's com.docker.backend connects to our ports for health checks.
# Killing it crashes Docker Desktop and brings down all containers.
echo "🧹 Clearing any zombie app processes on service ports..."
PORTS="8080 8081 8082 8083 8084 8085 9001 9002 9003 9004 9005 9006"
for PORT in $PORTS; do
  while IFS= read -r PROC_LINE; do
    PID=$(echo "$PROC_LINE" | awk '{print $2}')
    CMD=$(echo "$PROC_LINE" | awk '{print $1}')
    if echo "$CMD" | grep -qiE '^(java|python[0-9.]*|node|npm|uvicorn|gradlew?)$'; then
      echo "  Killing $CMD (PID $PID) on port $PORT"
      kill -9 "$PID" 2>/dev/null || true
    fi
  done < <(lsof -i :"$PORT" 2>/dev/null | tail -n +2)
done
sleep 1

echo "🚀 Starting GitOracle Infrastructure (Docker)..."
# Check if core infra is already running to avoid Docker Desktop crash bug
# triggered by 'docker compose up' events in certain Docker Desktop versions.
if docker ps --format "{{.Names}}" 2>/dev/null | grep -q "git-oracle-postgres"; then
  echo "✅ Infrastructure already running — skipping make infra-up"
else
  make infra-up
fi

echo "☕ Starting Java Microservices..."
set -a
source .env
set +a

cd java-backend
nohup ./gradlew :api-gateway:bootRun </dev/null > api-gateway.log 2>&1 &
echo $! > api-gateway.pid
nohup ./gradlew :error-ingestor:bootRun </dev/null > error-ingestor.log 2>&1 &
echo $! > error-ingestor.pid
nohup ./gradlew :orchestrator:bootRun </dev/null > orchestrator.log 2>&1 &
echo $! > orchestrator.pid
nohup ./gradlew :test-runner:bootRun </dev/null > test-runner.log 2>&1 &
echo $! > test-runner.pid
nohup ./gradlew :github-bot:bootRun </dev/null > github-bot.log 2>&1 &
echo $! > github-bot.pid
nohup ./gradlew :git-forensics:bootRun </dev/null > git-forensics.log 2>&1 &
echo $! > git-forensics.pid
cd ..

echo "🐍 Starting Python AI Agents..."
cd ai_core
source .venv/bin/activate
nohup python agents/fixer/main.py </dev/null > fixer.log 2>&1 &
echo $! > fixer.pid
nohup python agents/planner/main.py </dev/null > planner.log 2>&1 &
echo $! > planner.pid
nohup python agents/investigator/main.py </dev/null > investigator.log 2>&1 &
echo $! > investigator.pid
nohup python agents/reviewer/main.py </dev/null > reviewer.log 2>&1 &
echo $! > reviewer.pid
nohup python agents/prompt_registry/main.py </dev/null > prompt_registry.log 2>&1 &
echo $! > prompt_registry.pid
nohup python agents/guardrails/main.py </dev/null > guardrails.log 2>&1 &
echo $! > guardrails.pid
cd ..

echo "🧠 Starting Local LLM Server..."
cd llm-server
nohup ./start.sh </dev/null > llm.log 2>&1 &
echo $! > llm.pid
cd ..

echo "🎨 Starting Dashboard UI..."
cd dashboard
npm install > /dev/null 2>&1
nohup npm run dev </dev/null > dashboard.log 2>&1 &
echo $! > dashboard.pid
cd ..

echo "✅ All services have been launched in the background!"
echo "You can view the dashboard at: http://localhost:5173"
echo "To stop everything, you can run: kill \$(cat */*.pid) && make infra-down"
