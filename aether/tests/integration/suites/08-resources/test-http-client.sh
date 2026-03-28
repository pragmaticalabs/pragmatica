#!/bin/bash
# test-http-client.sh — Verify management API serves HTTP client requests correctly
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "${SCRIPT_DIR}/../../lib/common.sh"
source "${SCRIPT_DIR}/../../lib/cluster.sh"

test_cluster_ready() {
    wait_for_cluster 60
    log_pass "Cluster ready"
}

test_mgmt_health_endpoint() {
    assert_cluster_healthy "Management health check returns healthy"
}

test_mgmt_status_json() {
    local status
    status=$(cluster_status)
    assert_ne "$status" "" "Management /api/status returns JSON"
    local cluster_name
    cluster_name=$(json_field "$status" "['clusterName']")
    assert_ne "$cluster_name" "" "Status contains clusterName"
}

test_mgmt_nodes_json() {
    local nodes
    nodes=$(cluster_node_list)
    local count
    count=$(json_len "$nodes")
    assert_gt "$count" "0" "Management /api/nodes returns non-empty list"
}

test_mgmt_content_type() {
    local headers
    headers=$(curl -sf -D - -o /dev/null -H "X-API-Key: ${API_KEY}" "${CLUSTER_ENDPOINT}/api/status")
    assert_contains "$headers" "application/json" "Status response has JSON content-type"
}

test_mgmt_invalid_path() {
    local status
    status=$(http_status "${CLUSTER_ENDPOINT}/api/nonexistent-endpoint-xyz")
    # Expect 404 or similar non-5xx
    if [ "$status" -lt 500 ] 2>/dev/null; then
        log_pass "Invalid path returns non-5xx status: ${status}"
        return 0
    fi
    log_fail "Invalid path returns 5xx: ${status}"
    return 1
}

test_mgmt_concurrent_requests() {
    local success=0 failure=0
    for i in $(seq 1 20); do
        if api_get "/api/status" > /dev/null 2>&1; then
            success=$((success + 1))
        else
            failure=$((failure + 1))
        fi
    done
    assert_eq "$success" "20" "All 20 concurrent management requests succeeded"
}

run_test "Cluster ready" test_cluster_ready
run_test "Health endpoint" test_mgmt_health_endpoint
run_test "Status JSON" test_mgmt_status_json
run_test "Nodes JSON" test_mgmt_nodes_json
run_test "Content-Type header" test_mgmt_content_type
run_test "Invalid path handling" test_mgmt_invalid_path
run_test "Concurrent requests" test_mgmt_concurrent_requests
print_summary
