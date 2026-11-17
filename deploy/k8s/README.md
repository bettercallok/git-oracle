# GitOracle on k3s

The whole stack on one k3s node: the 6 Java services, the 7 agents, the
dashboard, and single-replica Postgres, Kafka, Neo4j, Redis and Qdrant. A
Cloudflare Tunnel gives it HTTPS without a public IP or an open port. Local
development stays on `start_local.sh`; this is for hosting.

```
deploy/k8s/
  base/              everything, with placeholder host, images and no secrets
  overlays/k3s/      your host, image tags, secrets (files you create), the tunnel
  deploy.sh          renders an overlay and applies it
```

## What runs where

| | Kubernetes object | Notes |
|---|---|---|
| api-gateway, dashboard | Deployment + Service, behind the Ingress | the only things reachable from outside |
| other Java services, agents | Deployment + Service | one replica each; ingress only from this namespace |
| test runs | one Job per run (`KubernetesJobSandbox`) | non-root, read-only root, no token, 512Mi; egress to the internet only |
| Postgres, Kafka, Neo4j, Redis, Qdrant | StatefulSet + claim | memory caps as in `docker-compose.infra.yml` |
| Kafka topics | Job running `infrastructure/kafka/create-topics.sh` | re-run on every deploy |
| Postgres schema | the orchestrator's Flyway migrations at startup | agent tables from `infrastructure/postgres/init.sql` |

The namespace enforces the `restricted` Pod Security Standard, so every pod
runs non-root with capabilities dropped. Observability (Langfuse, Prometheus,
Grafana, Kafka UI) is left out to save memory, as CI does.

## Size

Requests add up to about 3.3 GB and the limits to about 8 GB, on top of about
0.5 GB for k3s itself. An 8 GB VM (Azure B2as v2) is the minimum and will be
tight while a test Job runs. If pods are OOM-killed, use 16 GB (B4as v2).

## One-time setup

### 1. The VM
Ubuntu 24.04, 64 GB disk, auto-shutdown on. No inbound ports other than SSH
(or none, using the serial console).

```bash
curl -sfL https://get.k3s.io | sh -s - --write-kubeconfig-mode 644
```

k3s brings Traefik (the ingress controller), local-path storage and a
NetworkPolicy controller. Check that policies are enforced (see
[Checking the sandbox network](#checking-the-sandbox-network)).

### 2. Images
`.github/workflows/images.yml` pushes `ghcr.io/<owner>/gitoracle-*` from `main`.
New GHCR packages are private: make each one public (Package settings → Change
visibility), or add an image pull secret to the namespace. Pin `newTag` in
`overlays/k3s/kustomization.yaml` to a commit SHA for repeatable deploys.

### 3. The tunnel
In Cloudflare Zero Trust → Networks → Tunnels, create a tunnel and copy its
token. Add one public hostname (for example `gitoracle.example.com`) with
service `http://traefik.kube-system.svc.cluster.local:80`.

### 4. Your files
In `overlays/k3s/` (all four are git-ignored):

```bash
cp site.env.example site.env          # host, allowed repos, admins, LLM
cp secrets.env.example secrets.env    # fill every value; openssl rand -hex 32 for the random ones
echo "CLOUDFLARE_TUNNEL_TOKEN=<token>" > tunnel.env
cp /path/to/github-app.pem github-app.pem
```

### 5. The GitHub App
Point its URLs at the public host: webhook `https://<host>/webhook/github`,
callback `https://<host>/api/v1/auth/github/callback`, Setup URL
`https://<host>/api/v1/auth/github/setup` with "Redirect on update" ticked.
Organisation installations need the "Members: read" permission.

## Deploy

```bash
./deploy/k8s/deploy.sh k3s --dry-run   # server-side validation, changes nothing
./deploy/k8s/deploy.sh k3s
```

Then sign in at `https://<host>`. The legacy shared API key is off
(`GITORACLE_LEGACY_API_KEY_ENABLED=false`); make API keys on the dashboard's
API keys page.

Re-run `deploy.sh` after changing anything. Secrets and config are generated
with a content hash in their name, so a change rolls the pods that use it.

## Day to day

```bash
kubectl -n gitoracle get pods
kubectl -n gitoracle logs deploy/orchestrator -f
kubectl -n gitoracle get jobs -l app.kubernetes.io/component=test-sandbox
kubectl -n gitoracle rollout restart deploy/orchestrator
```

Stopping the VM stops everything; state lives on the claims and survives.
GitHub webhooks sent while it is off fail; redeliver them from the App's
Advanced settings. `PrStatusReconciler` catches up on PR merges and closes by itself.

## Checking the sandbox network

Test pods run the repository's own code, so they must not reach anything in
the cluster. Check it on the real node:

```bash
kubectl -n gitoracle run sandbox-check --rm -i --restart=Never --image=busybox:1.36 \
  --labels=app.kubernetes.io/component=test-sandbox -- \
  sh -c 'nc -z -w 3 postgres 5432 && echo "postgres: REACHABLE (policy not enforced)" || echo "postgres: blocked"; nc -z -w 5 github.com 443 && echo "internet: ok" || echo "internet: blocked"'
```

It should print `postgres: blocked` and `internet: ok`. Like most policy
engines, k3s' can't block traffic to the node's own address (which includes
the API server); sandbox pods carry no service-account token to use there.

## What was tested

On a k3s v1.30 cluster: the full overlay passes a server-side dry run with no
Pod Security warnings. Postgres, the orchestrator, Kafka, the topic Job, an
agent and the dashboard ran for real. Flyway built the schema, ShedLock
created its table, the orchestrator's consumer groups joined Kafka over SASL,
and the test runner's sandbox ran a real Job (`KubernetesJobSandboxClusterIT`).
The whole stack together, the tunnel, and NetworkPolicy enforcement were not
tested: the test cluster had 1.5 GB, and its policy controller (k3s inside
Docker Desktop) didn't filter traffic.
