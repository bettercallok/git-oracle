#!/usr/bin/env bash
# Renders an overlay and applies it: ./deploy/k8s/deploy.sh [overlay] [--dry-run]
#
# Not `kubectl apply -k`: the base reads infrastructure/postgres/init.sql and
# infrastructure/kafka/create-topics.sh from outside its own directory, which
# kustomize only allows with --load-restrictor LoadRestrictionsNone, a flag
# `apply -k` doesn't take. Using the files in place keeps one copy of each.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
overlay="${1:-k3s}"
dir="$here/overlays/$overlay"
mode="${2:-}"

[ -d "$dir" ] || { echo "No overlay $dir" >&2; exit 1; }
for f in site.env secrets.env tunnel.env github-app.pem; do
  [ -f "$dir/$f" ] || { echo "Missing $dir/$f (see $dir/kustomization.yaml)" >&2; exit 1; }
done
if grep -qE '^[A-Z_]+=$' "$dir/secrets.env"; then
  echo "Empty values in $dir/secrets.env:" >&2
  grep -E '^[A-Z_]+=$' "$dir/secrets.env" | sed 's/=$//; s/^/  /' >&2
  exit 1
fi

rendered="$(kubectl kustomize --load-restrictor LoadRestrictionsNone "$dir")"
if [ "$mode" = "--dry-run" ]; then
  printf '%s\n' "$rendered" | kubectl apply --dry-run=server -f -
  exit 0
fi

printf '%s\n' "$rendered" | kubectl apply -f -
# A Job's pod template can't be changed in place; the topic Job is safe to re-run.
kubectl -n gitoracle delete job kafka-topics --ignore-not-found >/dev/null
printf '%s\n' "$rendered" | kubectl apply -f - >/dev/null

echo
echo "Waiting for the pods (Java services take a minute or two)..."
kubectl -n gitoracle wait --for=condition=available deployment --all --timeout=600s
kubectl -n gitoracle get pods
