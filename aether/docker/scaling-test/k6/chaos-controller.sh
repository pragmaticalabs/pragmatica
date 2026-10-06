#!/bin/bash
# Chaos controller — runs in parallel with k6 soak test
# Injects failures at scheduled times to test cluster resilience
#
# Timeline (aligned with k6 soak-test.js phases):
#   Hour 1 (0-60 min):   Baseline — no chaos
#   Hour 2 (60-120 min): Kill worker-8, wait, restart
#   Hour 3 (120-180 min): Rolling restart of core nodes 2 and 3
#   Hour 4 (180-240 min): Recovery — no chaos
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
COMPOSE="docker compose -f $SCRIPT_DIR/../docker-compose.yml -f $SCRIPT_DIR/../docker-compose.workers.yml"
LOG_PREFIX="[chaos]"

log() {
    echo "$LOG_PREFIX $(date '+%H:%M:%S') $*"
}

wait_healthy() {
    local container="$1"
    local timeout="${2:-120}"
    log "Waiting for $container to be healthy (timeout: ${timeout}s)..."
    local elapsed=0
    while [ $elapsed -lt $timeout ]; do
        local status
        status=$(docker inspect --format='{{.State.Health.Status}}' "$container" 2>/dev/null || echo "not-found")
        if [ "$status" = "healthy" ]; then
            log "$container is healthy"
            return 0
        fi
        sleep 5
        elapsed=$((elapsed + 5))
    done
    log "WARNING: $container did not become healthy within ${timeout}s (status: $status)"
    return 1
}

log "=== Chaos Controller Started ==="
log "Total duration: 4 hours"

# ── Hour 1: Baseline (no chaos) ──────────────────────────────────────
log "Hour 1: Baseline — no chaos injected"
sleep 3600

# ── Hour 2: Worker kill ──────────────────────────────────────────────
log "=== Hour 2: Worker chaos phase ==="

log "Stopping aether-worker-8..."
$COMPOSE stop aether-worker-8
log "aether-worker-8 stopped. Observing cluster recovery for 5 minutes..."
sleep 300

log "Restarting aether-worker-8..."
$COMPOSE start aether-worker-8
wait_healthy "aether-worker-8" 120

log "Worker-8 back online. Sustaining load for remainder of hour 2..."
# Remaining time in hour 2: 60min - 5min(stop) - 5min(wait) - 2min(restart+health) ~ 48min
sleep 2880

# ── Hour 3: Rolling restart of core nodes ────────────────────────────
log "=== Hour 3: Core node rolling restart ==="

log "Stopping aether-node-2..."
$COMPOSE stop aether-node-2
sleep 60

log "Starting aether-node-2..."
$COMPOSE start aether-node-2
wait_healthy "aether-node-2" 120

log "aether-node-2 rejoined. Waiting 2 minutes before next disruption..."
sleep 120

log "Stopping aether-node-3..."
$COMPOSE stop aether-node-3
sleep 60

log "Starting aether-node-3..."
$COMPOSE start aether-node-3
wait_healthy "aether-node-3" 120

log "aether-node-3 rejoined. Sustaining load for remainder of hour 3..."
# Remaining time: 60min - 1min(stop) - 2min(health) - 2min(gap) - 1min(stop) - 2min(health) ~ 52min
sleep 3120

# ── Hour 4: Recovery (no chaos) ──────────────────────────────────────
log "=== Hour 4: Recovery — no chaos ==="
log "Monitoring cluster stability under sustained load..."
sleep 3600

log "=== Chaos Controller Complete ==="
