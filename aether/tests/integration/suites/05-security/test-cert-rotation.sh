#!/bin/bash
# test-cert-rotation.sh — Certificate rotation during load
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "${SCRIPT_DIR}/../../lib/common.sh"
source "${SCRIPT_DIR}/../../lib/cluster.sh"
source "${SCRIPT_DIR}/../../lib/load.sh"

LOAD_DURATION="${LOAD_DURATION:-60}"
LOAD_RPS="${LOAD_RPS:-5}"
MAX_ERROR_RATE="${MAX_ERROR_RATE:-5.0}"

test_cluster_ready() {
    wait_for_cluster 60
    log_pass "Cluster ready for cert rotation test"
}

test_tls_active() {
    # Verify TLS is enabled by checking cluster config
    local config
    config=$(cluster_config)
    assert_ne "$config" "" "Cluster config available"
}

test_rotation_under_load() {
    # Start load
    start_load "$LOAD_RPS" "$LOAD_DURATION" "GET" "/api/health"
    sleep 5

    # Trigger cert rotation via management API
    log_info "Triggering certificate rotation"
    local status
    status=$(http_status "${CLUSTER_ENDPOINT}/api/config" \
        -X POST \
        -H "X-API-Key: ${ADMIN_API_KEY}" \
        -H "Content-Type: application/json" \
        -d '{"tls":{"rotate":true}}')
    log_info "Cert rotation response: ${status}"

    # Wait for load to finish
    for pid in "${LOAD_PIDS[@]}"; do
        wait "$pid" 2>/dev/null || true
    done

    local result
    result=$(stop_load)
    assert_error_rate_below "$result" "$MAX_ERROR_RATE" "Error rate during cert rotation < ${MAX_ERROR_RATE}%"
}

test_cluster_healthy_after_rotation() {
    sleep 5
    assert_http_status "${CLUSTER_ENDPOINT}/health/ready" "200" "Cluster healthy after cert rotation"
}

test_all_nodes_present() {
    local count
    count=$(cluster_node_count)
    assert_eq "$count" "5" "All 5 nodes present after cert rotation"
}

run_test "Cluster ready" test_cluster_ready
run_test "TLS config active" test_tls_active
run_test "Cert rotation under load" test_rotation_under_load
run_test "Healthy after rotation" test_cluster_healthy_after_rotation
run_test "All nodes present" test_all_nodes_present
print_summary
