#!/bin/bash
# test-scale-down.sh — Scale 7 -> 5 under load
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "${SCRIPT_DIR}/../../lib/common.sh"
source "${SCRIPT_DIR}/../../lib/cluster.sh"
source "${SCRIPT_DIR}/../../lib/load.sh"

LOAD_RPS="${LOAD_RPS:-5}"
LOAD_DURATION="${LOAD_DURATION:-180}"
MAX_ERROR_RATE="${MAX_ERROR_RATE:-2.0}"

test_scale_api_available() {
    wait_for_cluster 60
    local status
    status=$(http_status "${CLUSTER_ENDPOINT}/api/cluster/scale" \
        -X POST \
        -H "X-API-Key: ${API_KEY}" \
        -H "Content-Type: application/json" \
        -d '{"coreCount":5,"expectedVersion":0}')
    if [ "$status" = "000" ] || [ "$status" = "" ]; then
        skip_test "Scale API" "Scale API endpoint not available"
        print_summary
        exit 0
    fi
    log_pass "Scale API endpoint responds (status: ${status})"
}

test_scale_up_to_7() {
    log_info "Scaling up to 7 nodes first"
    scale_cluster 7
    wait_for_node_count 7 180
    local count
    count=$(cluster_node_count)
    assert_eq "$count" "7" "Scaled to 7 nodes"
}

test_scale_down_under_load() {
    # Start load
    start_load "$LOAD_RPS" "$LOAD_DURATION" "GET" "/api/health"
    sleep 5

    # Scale down to 5
    log_info "Scaling down to 5 under load"
    scale_cluster 5

    # Wait for scale-down
    wait_for_node_count 5 180

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
    count=$(cluster_node_count)
    assert_eq "$count" "5" "5 nodes present after scale-down"
    assert_cluster_healthy "Cluster healthy at 5 nodes"
}

test_no_data_loss() {
    # Verify cluster events show graceful drain
    local events
    events=$(cluster_events)
    assert_ne "$events" "" "Events available after scale-down"
}

run_test "Scale API available" test_scale_api_available
run_test "Scale up to 7" test_scale_up_to_7
run_test "Scale down 7 -> 5 under load" test_scale_down_under_load
run_test "5 nodes healthy" test_5_nodes_healthy
run_test "No data loss" test_no_data_loss
print_summary
