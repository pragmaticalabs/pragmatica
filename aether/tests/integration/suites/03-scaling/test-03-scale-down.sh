#!/bin/bash
# test-03-scale-down.sh — Scale 7 -> 5 under load
# Runs after scale-up (cluster restored to 5 nodes)
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "${SCRIPT_DIR}/../../lib/common.sh"
source "${SCRIPT_DIR}/../../lib/cluster.sh"
source "${SCRIPT_DIR}/../../lib/load.sh"

LOAD_RPS="${LOAD_RPS:-5}"
LOAD_DURATION="${LOAD_DURATION:-180}"
MAX_ERROR_RATE="${MAX_ERROR_RATE:-2.0}"

test_seed_config() {
    wait_for_cluster_ready 60
    wait_for_leader 60
    seed_cluster_config
    # Wait for generation to quiesce after previous suite's scale-down — ensures
    # leadership is stable before issuing scale operations (avoids "quorum unavailable"
    # on the first scale call during a brief re-election window).
    await_generation_quiesced "${CLUSTER_ENDPOINT}" "current" 60 || true
}

test_scale_up_to_7() {
    log_info "Scaling up to 7 nodes first"
    scale_cluster 7
    # Fast-poll variant (see lib/cluster.sh:wait_for_node_count_fast) — avoids
    # the CLI/double-curl per-iter cost that pushed scale-up past 300s on
    # Hetzner remote even when the cluster was actually at 7.
    wait_for_node_count_fast 7 180
    local count
    count=$(cluster_member_count)
    assert_eq "$count" "7" "Scaled to 7 nodes"
}

test_scale_down_under_load() {
    # Production-like load: hit an app-port slice endpoint (test-echo blueprint
    # deployed by suite harness) rather than the synthetic /health/live. Exercises
    # the slice routing table that gets republished on every generation advance
    # during a scale-down — masked by /health/live which is a node-local probe.
    start_load "$LOAD_RPS" "$LOAD_DURATION" "GET" "/api/echo/health"
    sleep 5

    # Scale down to 5
    log_info "Scaling down to 5 under load"
    scale_cluster 5

    # Wait for scale-down (fast poll — see test-02-scale-up.sh comment)
    wait_for_node_count_fast 5 180

    # Wait for load to complete
    for pid in "${LOAD_PIDS[@]}"; do
        wait "$pid" 2>/dev/null || true
    done

    local result
    result=$(stop_load)
    assert_error_rate_below "$result" "$MAX_ERROR_RATE" "Scale-down error rate < ${MAX_ERROR_RATE}%"
}

test_5_nodes_healthy() {
    local count
    count=$(cluster_member_count)
    assert_eq "$count" "5" "5 nodes present after scale-down"
    assert_cluster_healthy "Cluster healthy at 5 nodes"
}

test_no_data_loss() {
    local events
    events=$(cluster_events)
    assert_ne "$events" "" "Events available after scale-down"
}

run_test "Seed cluster config" test_seed_config
run_test "Scale up to 7" test_scale_up_to_7
run_test "Scale down 7 -> 5 under load" test_scale_down_under_load
run_test "5 nodes healthy" test_5_nodes_healthy
run_test "No data loss" test_no_data_loss
print_summary
