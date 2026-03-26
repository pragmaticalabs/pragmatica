#!/bin/bash
# test-rolling-upgrade.sh — Rolling upgrade version under load
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "${SCRIPT_DIR}/../../lib/common.sh"
source "${SCRIPT_DIR}/../../lib/cluster.sh"
source "${SCRIPT_DIR}/../../lib/load.sh"

UPGRADE_ARTIFACT="${UPGRADE_ARTIFACT:-url-shortener}"
LOAD_DURATION="${LOAD_DURATION:-120}"
LOAD_RPS="${LOAD_RPS:-5}"
MAX_ERROR_RATE="${MAX_ERROR_RATE:-5.0}"

test_cluster_ready() {
    wait_for_cluster 60
    assert_eq "$(cluster_node_count)" "5" "5 nodes ready"
}

test_start_rolling_update() {
    local body="{\"artifact\":\"${UPGRADE_ARTIFACT}\",\"strategy\":\"rolling\",\"batchSize\":1}"
    local result
    result=$(rolling_update_start "$body")
    assert_ne "$result" "" "Rolling update started"

    # Extract update ID
    UPDATE_ID=$(echo "$result" | python3 -c "
import sys, json
data = json.load(sys.stdin)
for field in ['updateId', 'id', 'deploymentId']:
    if field in data:
        print(data[field])
        sys.exit(0)
print('')
" 2>/dev/null)
    log_info "Update ID: ${UPDATE_ID}"
}

test_rolling_update_under_load() {
    # Start load during the update
    start_load "$LOAD_RPS" "$LOAD_DURATION" "GET" "/api/health"

    # Wait for rolling update to complete
    wait_for "rolling update complete" \
        "rolling_update_status '${UPDATE_ID:-unknown}' | grep -qi 'complete\|finished\|done'" 300 5

    # Wait for load to finish
    for pid in "${LOAD_PIDS[@]}"; do
        wait "$pid" 2>/dev/null || true
    done

    local result
    result=$(stop_load)
    assert_error_rate_below "$result" "$MAX_ERROR_RATE" "Rolling update error rate < ${MAX_ERROR_RATE}%"
}

test_all_nodes_updated() {
    local count
    count=$(cluster_node_count)
    assert_eq "$count" "5" "All 5 nodes present after rolling update"
}

test_cluster_healthy_after_update() {
    assert_http_status "${CLUSTER_ENDPOINT}/health/ready" "200" "Cluster healthy after rolling update"
}

test_slices_active_after_update() {
    wait_for_slices_active 1 60
    local instances
    instances=$(slices_total_instances)
    assert_gt "$instances" "0" "Slices active after rolling update"
}

run_test "Cluster ready" test_cluster_ready
run_test "Start rolling update" test_start_rolling_update
run_test "Rolling update under load" test_rolling_update_under_load
run_test "All nodes updated" test_all_nodes_updated
run_test "Cluster healthy" test_cluster_healthy_after_update
run_test "Slices active" test_slices_active_after_update
print_summary
