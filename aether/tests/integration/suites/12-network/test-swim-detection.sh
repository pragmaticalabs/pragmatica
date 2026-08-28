#!/bin/bash
# test-swim-detection.sh — Kill a follower, verify SWIM/topology emits
# a departure event within the detection window, then wait for full recovery.
#
# Event-driven instead of snapshot polling: auto-heal is fast enough that
# /api/v1/cluster/topology can show the replacement before the poll sees the
# drop. /api/v1/events gives us an ordered, stable record of what happened.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "${SCRIPT_DIR}/../../lib/common.sh"
source "${SCRIPT_DIR}/../../lib/cluster.sh"
source "${SCRIPT_DIR}/../../lib/topology.sh"

DETECTION_TIMEOUT="${SWIM_DETECTION_TIMEOUT:-15}"

test_cluster_ready() {
    wait_for_cluster_ready 60
    # SWIM cold-boot suppression bypass: kills against a phase=COLD_BOOT cluster
    # produce UnknownObserved (not FaultyObserved), so no NODE_FAILED event fires.
    # Soft (log_warn) — docker-remote cluster B cumulative degradation can keep
    # phase=COLD_BOOT; fail-fast here cascades that infra issue into a 12-network
    # suite failure. Subsequent assertion will fail with a clearer "no event"
    # signal if the precondition really wasn't met.
    wait_for_phase "NORMAL" 180 || \
        log_warn "Cluster phase did not reach NORMAL within 180s — kill below may be silently absorbed by SWIM cold-boot suppression"
    # The restore baseline floor is deliberately "4+ READY, then settle" — an instant
    # ==5 assert races the trailing 5th core (bites harder now that restores converge
    # fast). Wait bounded for the settled state instead.
    wait_for "5 healthy cores (settled baseline)" '[ "$(cluster_active_core_count)" = "5" ]' 120
    # The bounded wait above IS the assertion: it fails the test on timeout (and this
    # function exits there under set -e), so reaching this line means 5 was observed.
    # Do NOT re-read cluster_active_core_count for a single-shot assert here — that
    # re-roll is a TOCTOU race against post-churn readiness flapping (a replacement
    # provisioned ~25s ago can transiently dip out of "healthy" between two reads;
    # bit run-2 of the final gate: wait PASSed at 20s, instant re-read got 4).
    log_pass "Initial: 5 healthy cores (settled — asserted by the bounded wait above)"
}

test_swim_detection_time() {
    local leader
    leader=$(cluster_leader)
    local victim
    victim=$(pick_non_leader "$leader" 1)
    : "${victim:=aether-b-node-4}"

    local baseline
    baseline=$(topology_now)
    local start_epoch
    start_epoch=$(now_epoch)

    KILLED_VICTIM="$victim"
    kill_node "$victim"

    # Scale the prod 15s budget by TIMEOUT_SCALE so cloud (TIMEOUT_SCALE=3 → 45s)
    # still gets a HARD ceiling, matching wait_for_node_departure's own scaling
    # convention in lib/topology.sh. Docker/localhost (scale=1) keeps the strict
    # 15s target. Detection in [budget+1, 60*scale] is a regression, not a warn.
    local effective_budget=$((DETECTION_TIMEOUT * ${TIMEOUT_SCALE:-1}))

    if ! wait_for_node_departure "$victim" "$baseline" 60; then
        log_fail "No NODE_LEFT/NODE_FAILED event for ${victim} within 60s (scaled by TIMEOUT_SCALE=${TIMEOUT_SCALE:-1})"
        return 1
    fi

    local elapsed
    elapsed=$(elapsed_since "$start_epoch")
    log_info "NODE_LEFT/NODE_FAILED observed for ${victim} after ${elapsed}s (budget: ${effective_budget}s)"

    if [ "$elapsed" -gt "$effective_budget" ]; then
        log_fail "SWIM detection took ${elapsed}s, exceeds budget ${effective_budget}s — production target is sub-${DETECTION_TIMEOUT}s detection (scaled ×${TIMEOUT_SCALE:-1})"
        return 1
    fi

    log_pass "SWIM detected node failure in ${elapsed}s (budget ${effective_budget}s)"
}

test_recovery_after_detection() {
    # Recovery is CTM's job — we already asserted SWIM detected the departure.
    # Assert the post-recovery invariant via the operator-visible signal: 5
    # healthy cores. Do NOT call `start_node "$KILLED_VICTIM"` — the killed node
    # has left membership, CTM has provisioned a replacement, and restarting the
    # original would leave the cluster in a stale-identity 6-node state.
    if ! wait_for "5 healthy cores after SWIM detection" \
        "[ \$(cluster_active_core_count) -eq 5 ]" 180; then
        log_fail "Cluster did not converge to 5 healthy cores within 180s after kill+auto-heal"
        return 1
    fi
    assert_cluster_healthy "Cluster recovered after SWIM detection"
}

run_test "Cluster ready (5 nodes)" test_cluster_ready
run_test "SWIM detection time" test_swim_detection_time
run_test "Recovery after detection" test_recovery_after_detection
print_summary
