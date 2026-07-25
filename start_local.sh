#!/bin/bash

# Ensure .env exists
if [ ! -f .env ]; then
  echo "Creating .env from .env.example..."
  make env-setup
fi

echo "🚀 Starting GitOracle Infrastructure (Docker)..."
make infra-up

echo "☕ Starting Java Microservices..."
set -a
source .env
set +a

cd java-backend
nohup ./gradlew :api-gateway:bootRun > api-gateway.log 2>&1 &
echo $! > api-gateway.pid
nohup ./gradlew :error-ingestor:bootRun > error-ingestor.log 2>&1 &
echo $! > error-ingestor.pid
nohup ./gradlew :orchestrator:bootRun > orchestrator.log 2>&1 &
echo $! > orchestrator.pid
nohup ./gradlew :test-runner:bootRun > test-runner.log 2>&1 &
echo $! > test-runner.pid
nohup ./gradlew :github-bot:bootRun > github-bot.log 2>&1 &
echo $! > github-bot.pid
nohup ./gradlew :git-forensics:bootRun > git-forensics.log 2>&1 &
echo $! > git-forensics.pid
cd ..

echo "🐍 Starting Python AI Agents..."
cd ai_core
source .venv/bin/activate
nohup python agents/fixer/main.py > fixer.log 2>&1 &
echo $! > fixer.pid
nohup python agents/planner/main.py > planner.log 2>&1 &
echo $! > planner.pid
nohup python agents/investigator/main.py > investigator.log 2>&1 &
echo $! > investigator.pid
nohup python agents/reviewer/main.py > reviewer.log 2>&1 &
echo $! > reviewer.pid
nohup python agents/prompt_registry/main.py > prompt_registry.log 2>&1 &
echo $! > prompt_registry.pid
nohup python agents/guardrails/main.py > guardrails.log 2>&1 &
echo $! > guardrails.pid
cd ..

echo "🧠 Starting Local LLM Server..."
cd llm-server
nohup ./start.sh > llm.log 2>&1 &
echo $! > llm.pid
cd ..

echo "🎨 Starting Dashboard UI..."
cd dashboard
npm install > /dev/null 2>&1
nohup npm run dev > dashboard.log 2>&1 &
echo $! > dashboard.pid
cd ..

echo "✅ All services have been launched in the background!"
echo "You can view the dashboard at: http://localhost:5173"
echo "To stop everything, you can run: kill \$(cat */*.pid) && make infra-down"
