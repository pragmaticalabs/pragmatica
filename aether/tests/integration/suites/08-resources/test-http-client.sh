#!/bin/bash
# test-http-client.sh — Verify management API serves HTTP client requests correctly
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "${SCRIPT_DIR}/../../lib/common.sh"
source "${SCRIPT_DIR}/../../lib/cluster.sh"

test_cluster_ready() {
    wait_for_cluster_ready 60
    wait_for_all_tasks_active 60 || log_warn "task groups not fully ACTIVE within 60s"
    log_pass "Cluster ready"
}

test_mgmt_health_endpoint() {
    assert_cluster_healthy "Management health check returns healthy"
}

test_mgmt_status_json() {
    local status
    status=$(cluster_status)
    assert_ne "$status" "" "Management /api/nodes/status returns JSON"
    # Use CLI --field which supports nested dot-path navigation
    local node_id
    node_id=$(aether_field status "cluster.nodes.0.id")
    assert_ne "$node_id" "" "Status contains node id in cluster.nodes"
}

test_mgmt_nodes_json() {
    # Query directly via CLI which knows how to count nodes
    local count
    count=$(aether_field topology coreCount)
    assert_gt "$count" "0" "Management cluster topology returns coreCount > 0"
}

test_mgmt_content_type() {
    local headers
    headers=$(curl -sf -D - -o /dev/null -H "X-API-Key: ${API_KEY}" "${CLUSTER_ENDPOINT}/api/nodes/status")
    assert_contains "$headers" "application/json" "Status response has JSON content-type"
}

test_mgmt_invalid_path() {
    # The contract: an AUTHENTICATED request to an unknown management path must return
    # 404. Without auth, the security middleware returns 401 BEFORE route lookup runs
    # (intentional: don't leak which paths exist to unauthenticated callers). Pass the
    # API key so the request reaches the router and surfaces the genuine "no such route"
    # 404 we're asserting against.
    assert_http_status "${CLUSTER_ENDPOINT}/api/nonexistent-endpoint-xyz" "404" \
        "Invalid management path returns 404" \
        -H "X-API-Key: ${API_KEY}"
}

test_mgmt_concurrent_requests() {
    local success=0 failure=0
    for i in $(seq 1 20); do
        if api_get "/api/nodes/status" > /dev/null 2>&1; then
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
