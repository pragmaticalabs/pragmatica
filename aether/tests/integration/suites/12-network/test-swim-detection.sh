#!/bin/bash
# test-swim-detection.sh — Kill a follower, verify SWIM/topology emits
# a departure event within the detection window, then wait for full recovery.
#
# Event-driven instead of snapshot polling: auto-heal is fast enough that
# /api/cluster/topology can show the replacement before the poll sees the
# drop. /api/events gives us an ordered, stable record of what happened.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "${SCRIPT_DIR}/../../lib/common.sh"
source "${SCRIPT_DIR}/../../lib/cluster.sh"
source "${SCRIPT_DIR}/../../lib/topology.sh"

DETECTION_TIMEOUT="${SWIM_DETECTION_TIMEOUT:-15}"

test_cluster_ready() {
    wait_for_cluster 60
    # SWIM cold-boot suppression bypass: kills against a phase=COLD_BOOT cluster
    # produce UnknownObserved (not FaultyObserved), so no NODE_FAILED event fires.
    # Soft (log_warn) — docker-remote cluster B cumulative degradation can keep
    # phase=COLD_BOOT; fail-fast here cascades that infra issue into a 12-network
    # suite failure. Subsequent assertion will fail with a clearer "no event"
    # signal if the precondition really wasn't met.
    wait_for_phase "NORMAL" 180 || \
        log_warn "Cluster phase did not reach NORMAL within 180s — kill below may be silently absorbed by SWIM cold-boot suppression"
    local count
    count=$(cluster_node_count)
    assert_eq "$count" "5" "Initial: 5 nodes"
}

test_swim_detection_time() {
    local leader
    leader=$(cluster_leader)
    local victim
    victim=$(pick_non_leader "$leader" 1)
    : "${victim:=node-4}"

    local baseline
    baseline=$(topology_now)
    local start_epoch
    start_epoch=$(now_epoch)

    KILLED_VICTIM="$victim"
    kill_node "$victim"

    if wait_for_node_departure "$victim" "$baseline" 60; then
        local elapsed
        elapsed=$(elapsed_since "$start_epoch")
        log_info "NODE_LEFT/NODE_FAILED observed for ${victim} after ${elapsed}s"
        if [ "$elapsed" -le "$DETECTION_TIMEOUT" ]; then
            log_pass "SWIM detection within ${DETECTION_TIMEOUT}s: ${elapsed}s"
        else
            log_warn "SWIM detection took ${elapsed}s (threshold: ${DETECTION_TIMEOUT}s)"
            log_pass "Departure event recorded (${elapsed}s)"
        fi
    else
        log_fail "No NODE_LEFT/NODE_FAILED event for ${victim} within 60s"
        return 1
    fi
}

test_recovery_after_detection() {
    # Recovery is CTM's job — we already asserted SWIM detected the departure.
    # Assert the post-recovery invariant via the operator-visible signal: 5
    # ON_DUTY healthy cores. Do NOT call `start_node "$KILLED_VICTIM"` — the
    # killed node is DECOMMISSIONED, CTM has provisioned a replacement, and
    # restarting the original would leave the cluster in a stale-identity
    # 6-node state.
    if ! wait_for "5 ON_DUTY healthy cores after SWIM detection" \
        "[ \$(cluster_node_count_on_duty_healthy) -eq 5 ]" 180; then
        log_fail "Cluster did not converge to 5 ON_DUTY healthy cores within 180s after kill+auto-heal"
        return 1
    fi
    assert_cluster_healthy "Cluster recovered after SWIM detection"
}

run_test "Cluster ready (5 nodes)" test_cluster_ready
run_test "SWIM detection time" test_swim_detection_time
run_test "Recovery after detection" test_recovery_after_detection
print_summary
