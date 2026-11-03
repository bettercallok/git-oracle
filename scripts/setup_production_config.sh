#!/usr/bin/env bash
# Moves a local GitOracle install off its bootstrap configuration, in one pass:
#
#   1. GitHub App credentials: from the deprecated java-backend/github-bot/.env
#      into the root .env (the only place GitHubAppCredentials should read).
#   2. Repository allow-list: which repos the default tenant may use
#      (GITORACLE_ALLOWED_REPOS, and the admin API if the stack is running).
#   3. A real API key (platform:admin + jobs), minted through the admin API with
#      the legacy key. It replaces GITORACLE_API_KEY in .env, so the CLI,
#      run_evals.py and the dashboard login keep working with one value.
#   4. Only after the new key is proven to work: GITORACLE_LEGACY_API_KEY_ENABLED=false.
#
# Run it yourself from the repo root, with the stack running (./start_local.sh):
#     scripts/setup_production_config.sh --repos owner/repo,owner/other
# Secrets are read from and written to .env directly; the only one printed is
# the new API key, once. .env is backed up first (.env.backup-<timestamp>).
# Afterwards restart: ./stop_local.sh && ./start_local.sh
set -euo pipefail

GATEWAY_URL="${GATEWAY_URL:-http://localhost:8080}"
ENV_FILE="${ENV_FILE:-.env}"
LEGACY_APP_ENV="${LEGACY_APP_ENV:-java-backend/github-bot/.env}"
REPOS=""
DISABLE_LEGACY=1

usage() {
  sed -n '2,19p' "$0" | sed 's/^# \{0,1\}//'
  echo "Options: --repos owner/a,owner/b   --keep-legacy-key   -h|--help"
}
while [ $# -gt 0 ]; do
  case "$1" in
    --repos) REPOS="${2:-}"; shift 2 ;;
    --repos=*) REPOS="${1#--repos=}"; shift ;;
    --keep-legacy-key) DISABLE_LEGACY=0; shift ;;
    -h|--help) usage; exit 0 ;;
    *) echo "Unknown option: $1" >&2; usage; exit 2 ;;
  esac
done

say()  { printf '\n\033[1m%s\033[0m\n' "$*"; }
ok()   { printf '  ✅ %s\n' "$*"; }
warn() { printf '  ⚠️  %s\n' "$*"; }
die()  { printf '  ❌ %s\n' "$*" >&2; exit 1; }

[ -f "$ENV_FILE" ] || die "$ENV_FILE not found — run this from the GitOracle repo root."
command -v python3 >/dev/null || die "python3 is required."
command -v curl >/dev/null || die "curl is required."

# KEY's last value in a dotenv file (empty if unset). Never printed.
env_get() { python3 - "$1" "$2" <<'PY'
import sys
path, key = sys.argv[1], sys.argv[2]
value = ""
try:
    for line in open(path, encoding="utf-8"):
        line = line.rstrip("\n")
        if line.startswith(key + "="):
            value = line[len(key) + 1:].strip().strip('"').strip("'")
except FileNotFoundError:
    pass
print(value)
PY
}

# Sets KEY=VALUE in .env: replaces the first assignment, drops later duplicates
# (dotenv's last-one-wins made duplicates silently override), else appends.
env_set() { python3 - "$ENV_FILE" "$1" "$2" <<'PY'
import os, sys
path, key, value = sys.argv[1], sys.argv[2], sys.argv[3]
lines = open(path, encoding="utf-8").read().splitlines()
out, done = [], False
for line in lines:
    if line.startswith(key + "="):
        if not done:
            out.append(f"{key}={value}")
            done = True
        continue
    out.append(line)
if not done:
    out.append(f"{key}={value}")
tmp = path + ".tmp"
with open(tmp, "w", encoding="utf-8") as f:
    f.write("\n".join(out) + "\n")
os.chmod(tmp, 0o600)
os.replace(tmp, path)
PY
}

is_real() { case "$1" in ""|your_*|change_me*|CHANGE_ME*) return 1 ;; *) return 0 ;; esac; }

# ─── 0. Backup ───────────────────────────────────────────────────────────────
BACKUP="$ENV_FILE.backup-$(date +%Y%m%d-%H%M%S)"
cp -p "$ENV_FILE" "$BACKUP"
chmod 600 "$BACKUP"
say "0. Backed up $ENV_FILE → $BACKUP"

# ─── 1. GitHub App credentials into the root .env ────────────────────────────
say "1. GitHub App credentials"
if is_real "$(env_get "$ENV_FILE" GITHUB_APP_ID)"; then
  ok "Already in $ENV_FILE — nothing to move."
elif [ -f "$LEGACY_APP_ENV" ] && is_real "$(env_get "$LEGACY_APP_ENV" GITHUB_APP_ID)"; then
  key_path="$(env_get "$LEGACY_APP_ENV" GITHUB_PRIVATE_KEY_PATH)"
  case "$key_path" in
    ""|/*) ;;
    *) key_path="$(cd "$(dirname "$LEGACY_APP_ENV")" && cd "$(dirname "$key_path")" && pwd)/$(basename "$key_path")" ;;
  esac
  [ -n "$key_path" ] && [ ! -f "$key_path" ] && die "The App private key named in $LEGACY_APP_ENV doesn't exist: $key_path"
  env_set GITHUB_APP_ID "$(env_get "$LEGACY_APP_ENV" GITHUB_APP_ID)"
  env_set GITHUB_INSTALLATION_ID "$(env_get "$LEGACY_APP_ENV" GITHUB_INSTALLATION_ID)"
  env_set GITHUB_PRIVATE_KEY_PATH "$key_path"
  mv "$LEGACY_APP_ENV" "$LEGACY_APP_ENV.migrated"
  chmod 600 "$LEGACY_APP_ENV.migrated"
  ok "Moved App ID, installation ID and key path into $ENV_FILE (key path made absolute)."
  ok "Renamed $LEGACY_APP_ENV → $LEGACY_APP_ENV.migrated (delete it once everything works)."
else
  warn "No real App credentials in $ENV_FILE or $LEGACY_APP_ENV — PRs can't be opened until GITHUB_APP_ID,"
  warn "GITHUB_INSTALLATION_ID and GITHUB_PRIVATE_KEY_PATH are set in $ENV_FILE."
fi

# ─── 2. Repository allow-list ────────────────────────────────────────────────
say "2. Repositories the default tenant may use"
existing="$(env_get "$ENV_FILE" GITORACLE_ALLOWED_REPOS)"
if [ -z "$REPOS" ] && [ -t 0 ]; then
  printf '  Repositories (owner/name, comma-separated)%s: ' "${existing:+ [currently: $existing]}"
  read -r REPOS
fi
merged="$(python3 - "$existing" "$REPOS" <<'PY'
import re, sys
seen, out = set(), []
for part in ",".join(sys.argv[1:]).split(","):
    p = part.strip()
    m = re.match(r"^(?:https?://(?:www\.)?github\.com/)?([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+?)(?:\.git)?/?$", p)
    if not p:
        continue
    if not m:
        print(f"  ⚠️  skipping '{p}' — not owner/name or a GitHub URL", file=sys.stderr)
        continue
    slug = f"{m.group(1)}/{m.group(2)}".lower()
    if slug not in seen:
        seen.add(slug); out.append(slug)
print(",".join(out))
PY
)"
if [ -n "$merged" ]; then
  env_set GITORACLE_ALLOWED_REPOS "$merged"
  ok "GITORACLE_ALLOWED_REPOS=$merged (applied to the default tenant at the next start)"
else
  warn "No repositories allowed — every job will be refused until some are added."
fi

# ─── 3–5 need the running gateway ────────────────────────────────────────────
legacy_key="$(env_get "$ENV_FILE" GITORACLE_API_KEY)"
if ! curl -fsS -m 5 -o /dev/null "$GATEWAY_URL/actuator/health"; then
  warn "The gateway at $GATEWAY_URL isn't answering, so no API key was minted."
  warn "Start the stack (./start_local.sh) and run this script again to finish."
  exit 0
fi
is_real "$legacy_key" || die "GITORACLE_API_KEY in $ENV_FILE is empty or a placeholder — it's needed once to mint the first real key."

api() { # METHOD PATH KEY [JSON] → prints "<http status> <body>"
  local body
  body="$(curl -sS -m 15 -X "$1" "$GATEWAY_URL$2" -H "X-API-Key: $3" -H 'Content-Type: application/json' \
          ${4:+--data "$4"} -w $'\n%{http_code}')"
  printf '%s %s' "${body##*$'\n'}" "${body%$'\n'*}"
}

if [ -n "$merged" ]; then
  IFS=',' read -r -a repo_list <<< "$merged"
  for repo in "${repo_list[@]}"; do
    reply="$(api POST /api/v1/admin/repos "$legacy_key" "{\"repo\":\"$repo\"}")"
    case "${reply%% *}" in
      200) ok "Allowed $repo now (no restart needed)" ;;
      404) warn "This version has no /api/v1/admin/repos yet; $repo applies at the next start."; break ;;
      *)   warn "Could not add $repo right away (HTTP ${reply%% *}); it applies at the next start." ;;
    esac
  done
fi

say "3. Minting a real API key"
reply="$(api POST /api/v1/admin/api-keys "$legacy_key" \
  "{\"name\":\"admin-$(hostname -s 2>/dev/null || echo local)-$(date +%Y%m%d)\",\"scopes\":[\"platform:admin\",\"jobs:read\",\"jobs:write\"]}")"
[ "${reply%% *}" = "200" ] || die "Minting failed (HTTP ${reply%% *}). Is GITORACLE_API_KEY the running gateway's legacy key?"
new_key="$(printf '%s' "${reply#* }" | python3 -c 'import json,sys; print(json.load(sys.stdin)["apiKey"])')"
key_prefix="$(printf '%s' "${reply#* }" | python3 -c 'import json,sys; print(json.load(sys.stdin)["keyPrefix"])')"
ok "Minted key $key_prefix (platform:admin, jobs:read, jobs:write)"

say "4. Checking the new key works"
status="$(api GET /api/v1/jobs "$new_key")"
[ "${status%% *}" = "200" ] || die "The new key was refused (HTTP ${status%% *}); nothing else was changed. Your legacy key still works."
ok "GET /api/v1/jobs with the new key → 200"
env_set GITORACLE_API_KEY "$new_key"
ok "GITORACLE_API_KEY in $ENV_FILE now holds the new key (CLI, run_evals.py and the dashboard use it)."

say "5. Legacy shared key"
if [ "$DISABLE_LEGACY" = 1 ]; then
  env_set GITORACLE_LEGACY_API_KEY_ENABLED false
  ok "GITORACLE_LEGACY_API_KEY_ENABLED=false (takes effect at the next start)"
else
  warn "Left enabled (--keep-legacy-key)."
fi

cat <<EOF

────────────────────────────────────────────────────────────────────────
  Your new API key — shown once, store it in a password manager:

      $new_key

  It's also saved as GITORACLE_API_KEY in $ENV_FILE. Sign in to the
  dashboard with it. Revoke it with:
      DELETE $GATEWAY_URL/api/v1/admin/api-keys/$key_prefix
────────────────────────────────────────────────────────────────────────

Next: restart so every change takes effect:
    ./stop_local.sh && ./start_local.sh
Backup of the previous .env: $BACKUP
EOF
