#!/bin/bash
# test-kill-under-load.sh — Kill node during active load, verify acceptable error rate
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "${SCRIPT_DIR}/../../lib/common.sh"
source "${SCRIPT_DIR}/../../lib/cluster.sh"
source "${SCRIPT_DIR}/../../lib/load.sh"

LOAD_DURATION="${LOAD_DURATION:-60}"
LOAD_RPS="${LOAD_RPS:-5}"
MAX_ERROR_RATE="${MAX_ERROR_RATE:-10.0}"

test_initial_state() {
    wait_for_cluster 60
    wait_for_leader 60
    local count
    count=$(cluster_node_count)
    assert_ge "$count" "5" "Initial: at least 5 nodes (${count})"
}

test_kill_during_load() {
    # Start background load against management health endpoint
    start_mgmt_load "$LOAD_RPS" "$LOAD_DURATION" "/health/live"

    # Wait for load to establish
    sleep 5

    # Kill a non-leader node
    local leader
    leader=$(cluster_leader)
    local victim
    victim=$(pick_non_leader "$leader")
    log_info "Killing ${victim} under load"
    kill_node "$victim"

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
    log_info "Waiting for CTM auto-heal to restore cluster to 5 nodes..."
    wait_for_node_count 5 180
    local count
    count=$(cluster_node_count)
    assert_eq "$count" "5" "Auto-heal restored cluster to 5 nodes"
}

cleanup() {
    log_info "Restoring all containers for clean state..."
    restart_all_nodes
    wait_for_node_count 5 120 || true
    wait_for_leader 90 || true
}

run_test "Initial 5 nodes" test_initial_state
run_test "Kill node during active load" test_kill_during_load
run_test "Cluster survives" test_cluster_survives
run_test "Auto-heal restores to 5" test_auto_heal
cleanup
print_summary
