#!/bin/bash
# test-scale-quorum-safety.sh — Scale to 1, verify rejection
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "${SCRIPT_DIR}/../../lib/common.sh"
source "${SCRIPT_DIR}/../../lib/cluster.sh"

test_scale_api_available() {
    wait_for_cluster 60
    local status
    status=$(http_status "${CLUSTER_ENDPOINT}/api/scale" \
        -X POST \
        -H "X-API-Key: ${API_KEY}" \
        -H "Content-Type: application/json" \
        -d '{"targetNodes":5}')
    if [ "$status" = "000" ] || [ "$status" = "" ]; then
        skip_test "Scale API" "Scale API endpoint not available"
        print_summary
        exit 0
    fi
    log_pass "Scale API endpoint responds (status: ${status})"
}

test_initial_state() {
    local count
    count=$(cluster_node_count)
    assert_ge "$count" "3" "Initial: at least 3 nodes"
}

test_reject_scale_to_1() {
    # Scaling below minimum (3) should be rejected
    local status
    status=$(http_status "${CLUSTER_ENDPOINT}/api/scale" \
        -X POST \
        -H "X-API-Key: ${API_KEY}" \
        -H "Content-Type: application/json" \
        -d '{"targetNodes":1}')

    # Expect 400 or 422 (rejected) — NOT 200
    if [ "$status" -ge 400 ] && [ "$status" -lt 500 ] 2>/dev/null; then
        log_pass "Scale to 1 rejected (status: ${status})"
        return 0
    fi
    log_fail "Scale to 1 was NOT rejected (status: ${status})"
    return 1
}

test_reject_scale_to_2() {
    local status
    status=$(http_status "${CLUSTER_ENDPOINT}/api/scale" \
        -X POST \
        -H "X-API-Key: ${API_KEY}" \
        -H "Content-Type: application/json" \
        -d '{"targetNodes":2}')

    if [ "$status" -ge 400 ] && [ "$status" -lt 500 ] 2>/dev/null; then
        log_pass "Scale to 2 rejected (status: ${status})"
        return 0
    fi
    log_fail "Scale to 2 was NOT rejected (status: ${status})"
    return 1
}

test_reject_scale_above_max() {
    # Config max is 15
    local status
    status=$(http_status "${CLUSTER_ENDPOINT}/api/scale" \
        -X POST \
        -H "X-API-Key: ${API_KEY}" \
        -H "Content-Type: application/json" \
        -d '{"targetNodes":20}')

    if [ "$status" -ge 400 ] && [ "$status" -lt 500 ] 2>/dev/null; then
        log_pass "Scale to 20 rejected (status: ${status})"
        return 0
    fi
    log_fail "Scale to 20 was NOT rejected (status: ${status})"
    return 1
}

test_cluster_unchanged() {
    local count
    count=$(cluster_node_count)
    assert_ge "$count" "3" "Cluster unchanged after rejected scale operations"
    assert_cluster_healthy "Cluster still healthy"
}

run_test "Scale API available" test_scale_api_available
run_test "Initial state" test_initial_state
run_test "Reject scale to 1" test_reject_scale_to_1
run_test "Reject scale to 2" test_reject_scale_to_2
run_test "Reject scale above max" test_reject_scale_above_max
run_test "Cluster unchanged" test_cluster_unchanged
print_summary
