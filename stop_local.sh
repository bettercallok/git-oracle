#!/bin/bash
#
# Safely stop all GitOracle app services (Java, Python agents, dashboard).
# Leaves Docker infrastructure alone — run `make infra-down` separately for that.
#
# ─────────────────────────────────────────────────────────────────────────────
# WHY THIS SCRIPT EXISTS — read before "simplifying" it
#
# The obvious one-liner for freeing a port is:
#
#     lsof -ti :8083 | xargs kill -9        # ← DO NOT DO THIS
#
# It will kill Docker Desktop.
#
# infrastructure/prometheus/prometheus.yml scrapes host.docker.internal:8081-8085
# and :9001-9006, so Prometheus (running in a container) holds an ESTABLISHED
# TCP connection to every one of those host ports. Those connections go through
# Docker's networking stack, which means `com.docker.backend` shows up in
# `lsof -i :<port>` output alongside the real service:
#
#     $ lsof -i :8081
#     COMMAND     PID   ...
#     java      27182  ...  TCP *:8081 (LISTEN)                    ← the service
#     com.docke 30382  ...  TCP localhost:55602->localhost:8081    ← Docker!
#
# `lsof -ti` prints PIDs *only*, with no command names to filter on, so it
# returns Docker's backend PID too. kill -9 on com.docker.backend crashes
# Docker Desktop and takes every container down with it.
#
# Always match on the command name, as done below.
# ─────────────────────────────────────────────────────────────────────────────

set -u

APP_PORTS="8080 8081 8082 8083 8084 8085 9001 9002 9003 9004 9005 9006 9007 5173"

# Kill only our own app processes listening on / connected to a port.
# Allowlist by command name, plus an explicit Docker denylist as belt-and-braces
# (lsof truncates COMMAND to 9 chars, hence the com.docke* glob).
safe_kill_port() {
  local port="$1" line pid cmd
  while IFS= read -r line; do
    pid=$(echo "$line" | awk '{print $2}')
    cmd=$(echo "$line" | awk '{print $1}')

    case "$cmd" in
      com.docke*|Docker*|docker*|vpnkit*) continue ;;
    esac

    if echo "$cmd" | grep -qiE '^(java|python[0-9.]*|node|npm|uvicorn|gradlew?)$'; then
      echo "  Stopping $cmd (PID $pid) on port $port"
      kill "$pid" 2>/dev/null || true
    fi
  done < <(lsof -i :"$port" 2>/dev/null | tail -n +2)
}

echo "🛑 Stopping GitOracle app services..."
for PORT in $APP_PORTS; do
  safe_kill_port "$PORT"
done

# Gradle daemons aren't bound to any service port, so the port sweep can't see
# them. Matching on the daemon's own class name can never hit a Docker process.
pkill -f "GradleDaemon" 2>/dev/null || true

# Give things a moment to exit cleanly, then escalate to -9 for stragglers.
sleep 3
for PORT in $APP_PORTS; do
  while IFS= read -r line; do
    pid=$(echo "$line" | awk '{print $2}')
    cmd=$(echo "$line" | awk '{print $1}')
    case "$cmd" in
      com.docke*|Docker*|docker*|vpnkit*) continue ;;
    esac
    if echo "$cmd" | grep -qiE '^(java|python[0-9.]*|node|npm|uvicorn|gradlew?)$'; then
      echo "  Force-killing $cmd (PID $pid) on port $PORT"
      kill -9 "$pid" 2>/dev/null || true
    fi
  done < <(lsof -i :"$PORT" 2>/dev/null | tail -n +2)
done

# Clean up stale pid files written by start_local.sh
rm -f java-backend/*.pid ai_core/*.pid dashboard/*.pid 2>/dev/null || true

echo "✅ App services stopped. Docker infrastructure left running."
echo "   To stop infrastructure too: make infra-down"
