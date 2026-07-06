#!/bin/bash
# ============================================================
# GitOracle — Kafka Topic Creation Script
# Called by kafka-init container after Kafka is healthy
# All 16 topics with appropriate partition/retention config
# ============================================================

set -e

KAFKA_BOOTSTRAP="kafka:9092"
REPLICATION=1
KAFKA_TOPICS="/opt/kafka/bin/kafka-topics.sh"

echo "⏳ Waiting for Kafka to be ready..."
until $KAFKA_TOPICS --bootstrap-server "$KAFKA_BOOTSTRAP" --list > /dev/null 2>&1; do
    echo "  Kafka not ready yet — retrying in 3s..."
    sleep 3
done
echo "✅ Kafka is ready."

echo ""
echo "📋 Creating GitOracle topics..."

# Helper function
create_topic() {
    local TOPIC=$1
    local PARTITIONS=${2:-3}
    local RETENTION_MS=${3:-604800000}   # default 7 days
    local CLEANUP_POLICY=${4:-delete}

    $KAFKA_TOPICS \
        --bootstrap-server "$KAFKA_BOOTSTRAP" \
        --create \
        --if-not-exists \
        --topic "$TOPIC" \
        --partitions "$PARTITIONS" \
        --replication-factor "$REPLICATION" \
        --config retention.ms="$RETENTION_MS" \
        --config cleanup.policy="$CLEANUP_POLICY" \
        --config compression.type=lz4

    echo "  ✓ $TOPIC"
}

# ─── Core pipeline topics ─────────────────────────────────────
create_topic "error-ingested"                3 604800000
create_topic "forensics-complete"            3 604800000
create_topic "investigation-complete"        3 604800000
create_topic "planning-complete"             3 604800000
create_topic "fix-generated"                 3 604800000
create_topic "tests-passed"                  3 604800000
create_topic "tests-failed"                  3 604800000
create_topic "pr-opened"                     3 604800000

# ─── Human interaction topics ─────────────────────────────────
create_topic "confirmation-required"         1 604800000
create_topic "human-confirmation-received"   1 604800000
create_topic "review-comment-received"       3 604800000
create_topic "review-reply-posted"           3 604800000

# ─── System / operational topics ─────────────────────────────
create_topic "job-escalated"                 1 2592000000
create_topic "feedback-received"             1 2592000000

# ─── Multi-tenancy / admin ────────────────────────────────────
create_topic "tenant-registered"             1 -1
create_topic "audit-event"                   6 2592000000

# ─── List all created topics ─────────────────────────────────
echo ""
echo "📊 All GitOracle topics:"
$KAFKA_TOPICS \
    --bootstrap-server "$KAFKA_BOOTSTRAP" \
    --list | grep -v "^__" | sort | awk '{print "  •", $0}'

echo ""
echo "✅ Kafka topic initialisation complete."
echo "   Topics: 16 | Partitions: 3 (core) / 1 (human) / 6 (audit)"
