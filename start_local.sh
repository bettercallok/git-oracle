#!/bin/bash

# Ensure .env exists
if [ ! -f .env ]; then
  echo "Creating .env from .env.example..."
  make env-setup
fi

# ─── Clear zombie processes on all service ports ───────────────
# IMPORTANT: Only kill Java/Python/Node processes — never Docker processes.
#
# infrastructure/prometheus/prometheus.yml scrapes host.docker.internal:8081-8085
# and :9001-9006, so Prometheus (in a container) holds an ESTABLISHED connection
# to every one of these host ports, and `com.docker.backend` therefore appears in
# `lsof -i :<port>` right next to the real service. That is why this loop matches
# on the *command name* and never uses the tempting `lsof -ti | xargs kill -9`
# shortcut: `-ti` prints bare PIDs with nothing to filter on, so it hands you
# Docker's backend PID too, and killing it crashes Docker Desktop along with
# every container. (This has actually happened — twice.) Use ./stop_local.sh.
echo "🧹 Clearing any zombie app processes on service ports..."
PORTS="8080 8081 8082 8083 8084 8085 9001 9002 9003 9004 9005 9006 9007"
for PORT in $PORTS; do
  while IFS= read -r PROC_LINE; do
    PID=$(echo "$PROC_LINE" | awk '{print $2}')
    CMD=$(echo "$PROC_LINE" | awk '{print $1}')
    # Explicit Docker denylist as belt-and-braces (lsof truncates COMMAND to
    # 9 chars, hence com.docke*) on top of the allowlist below.
    case "$CMD" in
      com.docke*|Docker*|docker*|vpnkit*) continue ;;
    esac
    if echo "$CMD" | grep -qiE '^(java|python[0-9.]*|node|npm|uvicorn|gradlew?)$'; then
      echo "  Killing $CMD (PID $PID) on port $PORT"
      kill -9 "$PID" 2>/dev/null || true
    fi
  done < <(lsof -i :"$PORT" 2>/dev/null | tail -n +2)
done

# Gradle daemons are NOT tied to any service port, so the loop above won't
# catch them — but each stale one is a lingering JVM that idles for hours and
# accumulates across restarts, which is what actually exhausts RAM over time
# (as opposed to the app JVMs themselves). Safe to kill: not Docker, and
# gradle.properties now sets org.gradle.daemon=false so none should respawn.
pkill -f "GradleDaemon" 2>/dev/null || true
sleep 1

echo "🚀 Starting GitOracle Infrastructure (Docker)..."
# Check if core infra is already running to avoid Docker Desktop crash bug
# triggered by 'docker compose up' events in certain Docker Desktop versions.
if docker ps --format "{{.Names}}" 2>/dev/null | grep -q "git-oracle-postgres"; then
  echo "✅ Infrastructure already running — skipping make infra-up"
else
  make infra-up
fi

# Docker isn't just for infra: test-runner's own sandbox for running a cloned
# repo's tests requires Docker too. It used to silently fall back to running
# the repo's own build command directly on this host whenever Docker wasn't
# reachable — that's arbitrary third-party code execution, so it now refuses
# instead (503 at startup, or a failed TestResult per job; see
# TestRunnerController's docker preflight). If Docker Desktop is ever stopped
# while services are running, test execution stops working rather than
# silently running unsandboxed — that's intentional.
if ! docker info >/dev/null 2>&1; then
  echo "⚠️  Docker daemon not reachable — test-runner will refuse to run tests until it is."
fi

echo "☕ Building Java Microservices..."
set -a
source .env
set +a

cd java-backend
# IMPORTANT: services are run as plain `java -jar`, never `./gradlew bootRun`.
# bootRun forks the app as a child of a Gradle build execution that blocks
# until the app stops — so even with --no-daemon, each bootRun'd service
# keeps a *second*, uncapped JVM (the Gradle build supervisor) alive for its
# entire runtime. Six services that way means 12 resident JVMs, not 6, which
# is what caused a second Docker-crashing memory exhaustion after the first
# fix (which only stopped daemons from *accumulating*, not this). Building
# the jars once with a single short-lived gradle invocation and then running
# them directly avoids a build JVM being resident at all while they run.
MODULES="api-gateway error-ingestor orchestrator test-runner github-bot git-forensics"
BOOTJAR_TASKS=""
for m in $MODULES; do BOOTJAR_TASKS="$BOOTJAR_TASKS :$m:bootJar"; done
./gradlew --no-daemon $BOOTJAR_TASKS -x test

jar_for() {
  # Some modules (e.g. api-gateway) override `version` in their own build.gradle,
  # so stale jars from an older version string can sit alongside the current one —
  # pick by most recent mtime, not alphabetical/find order, to always get the one
  # gradle just built.
  ls -t "$1"/build/libs/*.jar 2>/dev/null | grep -v -- '-plain\.jar$' | head -1
}

echo "☕ Starting Java Microservices..."
# Use an array, not a plain string, for the JVM flags — passing an unquoted
# string variable containing multiple flags to `java` is not reliably
# word-split in every invocation context and can collapse into one bad
# -Xmx argument ("Invalid maximum heap size"); an array avoids the ambiguity.
JVM_ARGS=(-Xmx224m -XX:MaxMetaspaceSize=160m -XX:+UseSerialGC -Xss256k)
ORCHESTRATOR_JVM_ARGS=(-Xmx320m -XX:MaxMetaspaceSize=192m -XX:+UseSerialGC -Xss256k)

nohup java "${JVM_ARGS[@]}" -jar "$(jar_for api-gateway)" </dev/null > api-gateway.log 2>&1 &
echo $! > api-gateway.pid
nohup java "${JVM_ARGS[@]}" -jar "$(jar_for error-ingestor)" </dev/null > error-ingestor.log 2>&1 &
echo $! > error-ingestor.pid
nohup java "${ORCHESTRATOR_JVM_ARGS[@]}" -jar "$(jar_for orchestrator)" </dev/null > orchestrator.log 2>&1 &
echo $! > orchestrator.pid
nohup java "${JVM_ARGS[@]}" -jar "$(jar_for test-runner)" </dev/null > test-runner.log 2>&1 &
echo $! > test-runner.pid
# github-bot's GitHubClient.java loads its own .env via a bare Dotenv.configure().load()
# with no explicit directory, i.e. it depends on the process CWD being java-backend/github-bot/
# (which ./gradlew :github-bot:bootRun set implicitly) — preserve that by launching from there.
# jar_for's path is relative to java-backend/, so once we cd into github-bot/ the jar is
# at build/libs/<name>.jar, not <name>.jar directly — keep the full relative suffix.
GITHUB_BOT_JAR_REL="build/libs/$(basename "$(jar_for github-bot)")"
(cd github-bot && nohup java "${JVM_ARGS[@]}" -jar "$GITHUB_BOT_JAR_REL" </dev/null > ../github-bot.log 2>&1 &
echo $! > ../github-bot.pid)
nohup java "${JVM_ARGS[@]}" -jar "$(jar_for git-forensics)" </dev/null > git-forensics.log 2>&1 &
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
nohup python -m agents.commit_analyst.main </dev/null > commit_analyst.log 2>&1 &
echo $! > commit_analyst.pid
cd ..

# LLM inference uses external API (configured via LLM_BASE_URL in .env)
# No local llama.cpp server needed.

echo "🎨 Starting Dashboard UI..."
cd dashboard
npm install > /dev/null 2>&1
nohup npm run dev </dev/null > dashboard.log 2>&1 &
echo $! > dashboard.pid
cd ..

echo "✅ All services have been launched in the background!"
echo "You can view the dashboard at: http://localhost:5173"
echo "To stop the app services:  ./stop_local.sh"
echo "To stop infrastructure too: ./stop_local.sh && make infra-down"
