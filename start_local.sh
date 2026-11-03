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

# ─── Process supervision ─────────────────────────────────────────────────────
# Every service used to be a bare `nohup … &`: if one crashed, nothing restarted
# it and nothing said so — the dashboard just stopped moving. Each service now
# runs inside a small restart loop: when the process exits it's logged to
# supervisor.log and restarted after a backoff (2s doubling to 60s, reset once
# it has stayed up 5 minutes). The loop ignores SIGHUP like nohup did, so
# services still survive closing this terminal. stop_local.sh writes
# $STOP_FLAG and kills the loops BEFORE killing services, so nothing respawns.
ROOT_DIR="$(pwd)"
STOP_FLAG="$ROOT_DIR/.gitoracle-stopping"
SUPERVISOR_LOG="$ROOT_DIR/supervisor.log"
rm -f "$STOP_FLAG"

# supervise <name> <logfile> <command...>
# Writes <logfile without .log>.pid (the service) and .supervisor.pid (the loop),
# relative to the current directory, like the old `echo $! > x.pid` did.
supervise() {
  local name="$1" log="$2"; shift 2
  local pidfile="${log%.log}.pid"
  : > "$log"
  (
    trap '' HUP
    backoff=2
    while [ ! -f "$STOP_FLAG" ]; do
      started=$(date +%s)
      "$@" </dev/null >>"$log" 2>&1 &
      child=$!
      echo "$child" > "$pidfile"
      wait "$child"; code=$?
      [ -f "$STOP_FLAG" ] && break
      ran=$(( $(date +%s) - started ))
      [ "$ran" -ge 300 ] && backoff=2
      echo "$(date -u +%Y-%m-%dT%H:%M:%SZ) $name exited (code $code) after ${ran}s — restarting in ${backoff}s" >> "$SUPERVISOR_LOG"
      sleep "$backoff"
      backoff=$(( backoff * 2 > 60 ? 60 : backoff * 2 ))
    done
  ) &
  echo $! > "${log%.log}.supervisor.pid"
}

echo "☕ Building Java Microservices..."
set -a
source .env
set +a

# The Java services run from java-backend/, where a repo-root-relative key path
# like ./secrets/github-app.pem doesn't resolve — the original reason the App
# credentials lived in a second .env. Make it absolute once, here.
case "${GITHUB_PRIVATE_KEY_PATH:-}" in
  ""|/*) ;;
  *) export GITHUB_PRIVATE_KEY_PATH="$ROOT_DIR/${GITHUB_PRIVATE_KEY_PATH#./}" ;;
esac

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
# Each service's own config also writes a log file (logging.file.name, e.g.
# orchestrator.log) — and since they run from java-backend/, that is the very
# file supervise already appends their console output to. Two writers, one
# file: every line appeared twice. Console output is what supervise captures
# (including crashes before logging starts), so turn the file appender off here.
export LOGGING_FILE_NAME=
# Use an array, not a plain string, for the JVM flags — passing an unquoted
# string variable containing multiple flags to `java` is not reliably
# word-split in every invocation context and can collapse into one bad
# -Xmx argument ("Invalid maximum heap size"); an array avoids the ambiguity.
JVM_ARGS=(-Xmx224m -XX:MaxMetaspaceSize=160m -XX:+UseSerialGC -Xss256k)
ORCHESTRATOR_JVM_ARGS=(-Xmx320m -XX:MaxMetaspaceSize=192m -XX:+UseSerialGC -Xss256k)

supervise api-gateway api-gateway.log java "${JVM_ARGS[@]}" -jar "$(jar_for api-gateway)"
supervise error-ingestor error-ingestor.log java "${JVM_ARGS[@]}" -jar "$(jar_for error-ingestor)"
supervise orchestrator orchestrator.log java "${ORCHESTRATOR_JVM_ARGS[@]}" -jar "$(jar_for orchestrator)"
supervise test-runner test-runner.log java "${JVM_ARGS[@]}" -jar "$(jar_for test-runner)"
# github-bot's GitHubClient.java loads its own .env via a bare Dotenv.configure().load()
# with no explicit directory, i.e. it depends on the process CWD being java-backend/github-bot/
# (which ./gradlew :github-bot:bootRun set implicitly) — preserve that by launching from there.
# jar_for's path is relative to java-backend/, so once we cd into github-bot/ the jar is
# at build/libs/<name>.jar, not <name>.jar directly — keep the full relative suffix.
GITHUB_BOT_JAR_REL="build/libs/$(basename "$(jar_for github-bot)")"
(cd github-bot && supervise github-bot ../github-bot.log java "${JVM_ARGS[@]}" -jar "$GITHUB_BOT_JAR_REL")
supervise git-forensics git-forensics.log java "${JVM_ARGS[@]}" -jar "$(jar_for git-forensics)"
cd ..

echo "🐍 Starting Python AI Agents..."
cd ai_core
source .venv/bin/activate
supervise fixer fixer.log python agents/fixer/main.py
supervise planner planner.log python agents/planner/main.py
supervise investigator investigator.log python agents/investigator/main.py
supervise reviewer reviewer.log python agents/reviewer/main.py
supervise prompt_registry prompt_registry.log python agents/prompt_registry/main.py
supervise guardrails guardrails.log python agents/guardrails/main.py
supervise commit_analyst commit_analyst.log python -m agents.commit_analyst.main
cd ..

# LLM inference uses external API (configured via LLM_BASE_URL in .env)
# No local llama.cpp server needed.

# SKIP_DASHBOARD=1 for headless runs (the CI eval workflow) — nothing there
# looks at the UI, and npm install would add minutes for no reason.
if [ "${SKIP_DASHBOARD:-0}" != "1" ]; then
  echo "🎨 Starting Dashboard UI..."
  cd dashboard
  npm install > /dev/null 2>&1
  supervise dashboard dashboard.log npm run dev
  cd ..
fi

echo "✅ All services have been launched in the background!"
echo "You can view the dashboard at: http://localhost:5173"
echo "Crash restarts are logged to: supervisor.log"
echo "To stop the app services:  ./stop_local.sh"
echo "To stop infrastructure too: ./stop_local.sh && make infra-down"
