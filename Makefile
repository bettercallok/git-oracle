# ============================================================
# GitOracle — Makefile
# Convenience commands for the full development lifecycle
# ============================================================

.PHONY: help infra-up infra-down infra-logs infra-ps \
        infra-reset env-check neo4j-init kafka-list \
        open-grafana open-langfuse open-kafka open-neo4j \
        health

COMPOSE_INFRA = docker compose -f docker-compose.infra.yml
SHELL := /bin/bash

# ─── Default target ───────────────────────────────────────────
help:
	@echo ""
	@echo "  GitOracle — Available Commands"
	@echo "  ──────────────────────────────────────────────────"
	@echo ""
	@echo "  Infrastructure (Layer 0)"
	@echo "  ─────────────────────────"
	@echo "  make env-setup        Copy .env.example → .env (edit before running)"
	@echo "  make infra-up         Start all infrastructure services"
	@echo "  make infra-down       Stop all infrastructure services (keep data)"
	@echo "  make infra-reset      ⚠️  Stop + DELETE all volumes (full reset)"
	@echo "  make infra-logs       Tail logs from all infrastructure services"
	@echo "  make infra-ps         Show status of all containers"
	@echo "  make health           Check health of all services"
	@echo ""
	@echo "  Database"
	@echo "  ─────────"
	@echo "  make pg-shell         Open PostgreSQL interactive shell"
	@echo "  make neo4j-init       Re-run Neo4j schema init"
	@echo "  make kafka-list       List all Kafka topics"
	@echo "  make kafka-topics     Show topic details and partition counts"
	@echo "  make qdrant-info      Show Qdrant collections"
	@echo ""
	@echo "  Observability"
	@echo "  ─────────────"
	@echo "  make open-grafana     Open Grafana in browser (localhost:3001)"
	@echo "  make open-langfuse    Open Langfuse in browser (localhost:3002)"
	@echo "  make open-kafka       Open Kafka UI in browser (localhost:8089)"
	@echo "  make open-neo4j       Open Neo4j Browser (localhost:7474)"
	@echo ""

# ─── Environment ─────────────────────────────────────────────
env-setup:
	@if [ -f .env ]; then \
		echo "⚠️  .env already exists. Not overwriting."; \
	else \
		cp .env.example .env; \
		echo "✅ .env created from .env.example"; \
		echo "   ➡️  Edit .env and fill in all required values before running."; \
	fi

env-check:
	@echo "🔍 Checking required environment variables..."
	@set -a && source .env && set +a && \
	MISSING=""; \
	for VAR in POSTGRES_PASSWORD NEO4J_AUTH REDIS_PASSWORD \
	           GRAFANA_ADMIN_PASSWORD LANGFUSE_NEXTAUTH_SECRET LANGFUSE_SALT; do \
		if [ -z "$${!VAR}" ]; then MISSING="$$MISSING $$VAR"; fi; \
	done; \
	if [ -n "$$MISSING" ]; then \
		echo "❌ Missing required variables:$$MISSING"; \
		echo "   Run: make env-setup, then edit .env"; \
		exit 1; \
	else \
		echo "✅ All required environment variables are set."; \
	fi

# ─── Infrastructure ───────────────────────────────────────────
infra-up: env-check
	@echo "🚀 Starting GitOracle infrastructure..."
	@$(COMPOSE_INFRA) up -d --remove-orphans
	@echo ""
	@echo "✅ Infrastructure starting. Waiting for health checks..."
	@sleep 5
	@$(MAKE) health
	@echo ""
	@echo "  🌐 Service URLs:"
	@echo "     PostgreSQL  → localhost:$$(grep POSTGRES_PORT .env | cut -d= -f2 || echo 5432)"
	@echo "     Neo4j       → http://localhost:7474  (Browser UI)"
	@echo "     Redis       → localhost:6379"
	@echo "     Qdrant      → http://localhost:6333/dashboard"
	@echo "     Kafka       → localhost:9092 | UI: http://localhost:8089"
	@echo "     Prometheus  → http://localhost:9090"
	@echo "     Grafana     → http://localhost:3001  (admin / your-password)"
	@echo "     Langfuse    → http://localhost:3002"
	@echo ""

infra-down:
	@echo "🛑 Stopping GitOracle infrastructure (data preserved)..."
	@$(COMPOSE_INFRA) down
	@echo "✅ Stopped."

infra-reset:
	@echo "⚠️  WARNING: This will DELETE all data volumes!"
	@read -p "Type 'yes' to confirm: " CONFIRM && [ "$$CONFIRM" = "yes" ] || exit 1
	@$(COMPOSE_INFRA) down -v --remove-orphans
	@echo "✅ All infrastructure containers and volumes removed."

infra-logs:
	@$(COMPOSE_INFRA) logs -f --tail=50

infra-ps:
	@$(COMPOSE_INFRA) ps

# ─── Health Checks ────────────────────────────────────────────
health:
	@echo "🏥 GitOracle Infrastructure Health:"
	@echo "   ─────────────────────────────────────────────"
	@_check() { \
		NAME=$$1; CMD=$$2; \
		if eval "$$CMD" > /dev/null 2>&1; then \
			printf "   ✅ %-20s HEALTHY\n" "$$NAME"; \
		else \
			printf "   ❌ %-20s UNREACHABLE\n" "$$NAME"; \
		fi; \
	}; \
	source .env 2>/dev/null; \
	_check "PostgreSQL" "docker exec git-oracle-postgres pg_isready -U $${POSTGRES_USER:-gitOracle}"; \
	_check "Neo4j" "curl -sf http://localhost:7474 >/dev/null"; \
	_check "Redis" "docker exec git-oracle-redis redis-cli -a '$${REDIS_PASSWORD}' ping"; \
	_check "Qdrant" "curl -sf http://localhost:6333/readyz >/dev/null"; \
	_check "Kafka" "docker exec git-oracle-kafka kafka-topics.sh --bootstrap-server localhost:9092 --list >/dev/null 2>&1"; \
	_check "Prometheus" "curl -sf http://localhost:9090/-/healthy >/dev/null"; \
	_check "Grafana" "curl -sf http://localhost:3001/api/health >/dev/null"; \
	_check "Langfuse" "curl -sf http://localhost:3002/api/public/health >/dev/null"

# ─── Database Tools ───────────────────────────────────────────
pg-shell:
	@source .env && docker exec -it git-oracle-postgres \
		psql -U $${POSTGRES_USER:-gitOracle} -d $${POSTGRES_DB:-gitOracle}

neo4j-init:
	@echo "🔄 Re-running Neo4j schema init..."
	@source .env && docker exec git-oracle-neo4j \
		cypher-shell -u neo4j -p $${POSTGRES_PASSWORD} \
		-a bolt://localhost:7687 \
		--file /neo4j-init/init.cypher
	@echo "✅ Neo4j schema applied."

kafka-list:
	@docker exec git-oracle-kafka \
		kafka-topics.sh --bootstrap-server localhost:9092 --list | grep -v "^__" | sort

kafka-topics:
	@docker exec git-oracle-kafka \
		kafka-topics.sh --bootstrap-server localhost:9092 --describe | grep -v "^__"

qdrant-info:
	@curl -s http://localhost:6333/collections | python3 -m json.tool

# ─── Browser shortcuts (macOS) ────────────────────────────────
open-grafana:
	@open http://localhost:3001

open-langfuse:
	@open http://localhost:3002

open-kafka:
	@open http://localhost:8089

open-neo4j:
	@open http://localhost:7474

open-qdrant:
	@open http://localhost:6333/dashboard
