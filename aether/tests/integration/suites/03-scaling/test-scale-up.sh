#!/bin/bash
# test-scale-up.sh — Scale 3 -> 7 under load
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

test_scale_down_to_3() {
    log_info "Scaling down to 3 nodes first"
    scale_cluster 3
    wait_for_node_count 3 120
    local count
    count=$(cluster_node_count)
    assert_eq "$count" "3" "Scaled to 3 nodes"
}

test_scale_up_under_load() {
    # Start load
    start_load "$LOAD_RPS" "$LOAD_DURATION" "GET" "/api/health"
    sleep 5

    # Scale up to 7
    log_info "Scaling up to 7 under load"
    scale_cluster 7

    # Wait for all 7 nodes
    wait_for_node_count 7 180

    # Wait for load to complete
    for pid in "${LOAD_PIDS[@]}"; do
        wait "$pid" 2>/dev/null || true
    done

    local result
    result=$(stop_load)
    assert_error_rate_below "$result" "$MAX_ERROR_RATE" "Scale-up error rate < ${MAX_ERROR_RATE}%"
}

test_7_nodes_healthy() {
    local count
    count=$(cluster_node_count)
    assert_eq "$count" "7" "7 nodes present"
    assert_cluster_healthy "Cluster healthy at 7 nodes"
}

# Restore to 5 for subsequent tests
test_restore_to_5() {
    scale_cluster 5
    wait_for_node_count 5 120
    local count
    count=$(cluster_node_count)
    assert_eq "$count" "5" "Restored to 5 nodes"
}

run_test "Scale API available" test_scale_api_available
run_test "Scale down to 3" test_scale_down_to_3
run_test "Scale up 3 -> 7 under load" test_scale_up_under_load
run_test "7 nodes healthy" test_7_nodes_healthy
run_test "Restore to 5" test_restore_to_5
print_summary
