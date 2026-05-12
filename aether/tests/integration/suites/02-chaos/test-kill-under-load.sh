#!/bin/bash
# test-kill-under-load.sh — Kill node during active load, verify acceptable error rate
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "${SCRIPT_DIR}/../../lib/common.sh"
source "${SCRIPT_DIR}/../../lib/cluster.sh"
source "${SCRIPT_DIR}/../../lib/load.sh"
source "${SCRIPT_DIR}/../../lib/topology.sh"
source "${SCRIPT_DIR}/../../lib/generation.sh"

LOAD_DURATION="${LOAD_DURATION:-60}"
LOAD_RPS="${LOAD_RPS:-5}"
MAX_ERROR_RATE="${MAX_ERROR_RATE:-10.0}"

test_initial_state() {
    wait_for_cluster 60
    # Wait for phase=NORMAL to bypass SWIM cold-boot suppression of NODE_FAILED events.
    wait_for_phase "NORMAL" 180 || log_warn "Cluster phase still COLD_BOOT; chaos kill may produce UnknownObserved (no NODE_FAILED event)"
    wait_for_leader 60
    local count
    count=$(cluster_node_count)
    assert_ge "$count" "5" "Initial: at least 5 nodes (${count})"
}

test_kill_during_load() {
    # Start background load against management health endpoint
    start_mgmt_load "$LOAD_RPS" "$LOAD_DURATION" "/health/live"

    # Load establishment is genuinely time-based (load.sh ramps RPS via sleep);
    # this is not a chaos-timing sleep, so it stays.
    sleep 5

    # Kill a non-leader node
    local leader
    leader=$(cluster_leader)
    local victim
    victim=$(pick_non_leader "$leader")
    KILLED_VICTIM="$victim"

    # Capture topology baseline BEFORE the kill so the event-driven barrier
    # below scopes its event search correctly.
    local baseline
    baseline=$(topology_now)

    log_info "Killing ${victim} under load"
    kill_node "$victim"

    # Event-driven barrier: assert SWIM detected the failure mid-load. Without
    # this we'd silently pass even if SWIM detection broke entirely (load
    # finishes, error rate happens to be acceptable, no one notices).
    if ! wait_for_node_departure "$victim" "$baseline" 60; then
        log_fail "No NODE_LEFT/NODE_FAILED event for ${victim} within 60s under load"
        return 1
    fi
    log_pass "Departure of ${victim} observed under load"

    # Let load continue through the disruption
    log_info "Waiting for load to complete"
    for pid in "${LOAD_PIDS[@]}"; do
        wait "$pid" 2>/dev/null || true
    done

    local result
    result=$(stop_load)
    assert_error_rate_below "$result" "$MAX_ERROR_RATE" "Error rate during kill < ${MAX_ERROR_RATE}%"
}

test_cluster_survives() {
    local health
    health=$(aether_field health status)
    assert_eq "$health" "healthy" "Cluster healthy after kill-under-load"
}

test_auto_heal() {
    log_info "Waiting for CTM auto-heal to quiesce at the post-replacement generation..."
    wait_for_node_count 5 180 || {
        log_fail "Cluster did not quiesce after auto-heal"
        return 1
    }
    local count
    count=$(cluster_node_count)
    assert_eq "$count" "5" "Auto-heal restored cluster to exactly 5 nodes"
}

cleanup() {
    # Semantic baseline restore — see test-kill-leader.sh:cleanup() for the
    # rationale. `restore_cluster_baseline` consumes the same operator-visible
    # signals (lifecycle, generation, phase) the next suite will read.
    restore_cluster_baseline || \
        log_warn "cleanup: restore_cluster_baseline reported non-zero; subsequent suites may inherit cluster churn"
}

run_test "Initial 5 nodes" test_initial_state
run_test "Kill node during active load" test_kill_during_load
run_test "Cluster survives" test_cluster_survives
run_test "Auto-heal restores to 5" test_auto_heal
cleanup
print_summary
