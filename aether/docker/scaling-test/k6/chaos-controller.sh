#!/bin/bash
# Chaos controller — runs in parallel with k6 soak test
# Injects failures at scheduled times to test cluster resilience
#
# Timeline (aligned with k6 soak-test.js phases):
#   Hour 1 (0-60 min):   Baseline — no chaos
#   Hour 2 (60-120 min): Kill worker-8, wait for its replacement
#   Hour 3 (120-180 min): Kill core nodes 2 and 3 one at a time, wait for each replacement
#
# A killed node is NEVER relaunched under its own id (#1543: same-NodeId restart is refused).
# The only recovery is CTM auto-heal, which provisions a replacement under a FRESH node id;
# the controller waits for that container (label aether.provisioned-by=ctm) and reports it.
#   Hour 4 (180-240 min): Recovery — no chaos
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LOG_PREFIX="[chaos]"

log() {
    echo "$LOG_PREFIX $(date '+%H:%M:%S') $*"
}

# Count the CTM-provisioned (auto-heal) containers: each one is a replacement under a fresh node id.
replacement_count() {
    docker ps --filter label=aether.provisioned-by=ctm -q 2>/dev/null | wc -l | tr -d ' '
}

# Wait for auto-heal to provision a NEW replacement after a kill. Never starts the killed container.
# Returns 1 on timeout so the log shows the cluster stayed degraded; the soak continues regardless.
await_replacement() {
    local before="$1"
    local timeout="${2:-300}"
    log "Waiting for an auto-heal replacement (fresh node id; ${before} CTM container(s) now, timeout: ${timeout}s)..."
    local elapsed=0
    while [ $elapsed -lt $timeout ]; do
        local now
        now=$(replacement_count)
        if [ "$now" -gt "$before" ]; then
            log "Replacement provisioned (${now} CTM container(s))"
            return 0
        fi
        sleep 5
        elapsed=$((elapsed + 5))
    done
    log "WARNING: no auto-heal replacement appeared within ${timeout}s - cluster stays degraded (this compose project must mount the docker socket for auto-heal)"
    return 1
}

log "=== Chaos Controller Started ==="
log "Total duration: 4 hours"

# ── Hour 1: Baseline (no chaos) ──────────────────────────────────────
log "Hour 1: Baseline — no chaos injected"
sleep 3600

# ── Hour 2: Worker kill ──────────────────────────────────────────────
log "=== Hour 2: Worker chaos phase ==="

log "Killing aether-worker-8..."
BEFORE=$(replacement_count)
docker kill aether-worker-8
await_replacement "$BEFORE" 300 || true

log "Worker-8 chaos done. Sustaining load for remainder of hour 2..."
sleep 2880

# ── Hour 3: Core node kill + replacement ────────────────────────────
log "=== Hour 3: Core node kill + replacement ==="

for victim in aether-node-2 aether-node-3; do
    log "Killing ${victim}..."
    BEFORE=$(replacement_count)
    docker kill "$victim"
    await_replacement "$BEFORE" 300 || true
    log "${victim} chaos done. Waiting 2 minutes before next disruption..."
    sleep 120
done

log "Sustaining load for remainder of hour 3..."
sleep 3000

# ── Hour 4: Recovery (no chaos) ──────────────────────────────────────
log "=== Hour 4: Recovery — no chaos ==="
log "Monitoring cluster stability under sustained load..."
sleep 3600

log "=== Chaos Controller Complete ==="
