#!/bin/bash
# test-soak-4h.sh — 4-hour sustained load, measure drift
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "${SCRIPT_DIR}/../../lib/common.sh"
source "${SCRIPT_DIR}/../../lib/cluster.sh"
source "${SCRIPT_DIR}/../../lib/load.sh"

SOAK_DURATION="${SOAK_DURATION:-14400}"  # 4 hours default
SOAK_RPS="${SOAK_RPS:-10}"
SOAK_LOG="/tmp/sustained_load_soak.log"
MAX_ERROR_RATE="${MAX_ERROR_RATE:-1.0}"

test_cluster_baseline() {
    wait_for_cluster 60
    local count
    count=$(cluster_node_count)
    assert_eq "$count" "5" "Baseline: 5 nodes"
}

test_soak_load() {
    local start_nodes
    start_nodes=$(cluster_node_count)

    log_info "Starting ${SOAK_DURATION}s soak test at ${SOAK_RPS} rps"
    rm -f "$SOAK_LOG"

    start_sustained_load "$SOAK_RPS" "$SOAK_DURATION" "GET" "/api/health" "" "$SOAK_LOG"

    # Wait for load to complete
    log_info "Soak test running — waiting ${SOAK_DURATION}s"
    for pid in "${LOAD_PIDS[@]}"; do
        wait "$pid" 2>/dev/null || true
    done

    local result
    result=$(stop_load)
    assert_error_rate_below "$result" "$MAX_ERROR_RATE" "Soak error rate below ${MAX_ERROR_RATE}%"
}

test_no_node_drift() {
    local end_nodes
    end_nodes=$(cluster_node_count)
    assert_eq "$end_nodes" "5" "No node drift: still 5 nodes after soak"
}

test_cluster_still_healthy() {
    assert_http_status "${CLUSTER_ENDPOINT}/health/ready" "200" "Cluster still healthy after soak"
}

test_no_leader_change() {
    local leader
    leader=$(cluster_leader)
    assert_ne "$leader" "" "Leader still present after soak"
}

run_test "Cluster baseline" test_cluster_baseline
run_test "4-hour soak under load" test_soak_load
run_test "No node drift" test_no_node_drift
run_test "Cluster still healthy" test_cluster_still_healthy
run_test "Leader still present" test_no_leader_change
print_summary
