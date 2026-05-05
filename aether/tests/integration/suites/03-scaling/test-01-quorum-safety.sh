#!/bin/bash
# test-01-quorum-safety.sh — Verify rejection of unsafe scale operations
# Runs first: no actual scaling, just validates rejection of invalid requests
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "${SCRIPT_DIR}/../../lib/common.sh"
source "${SCRIPT_DIR}/../../lib/cluster.sh"

test_seed_config() {
    wait_for_cluster 60
    wait_for_leader 60
    seed_cluster_config
}

test_initial_state() {
    local count
    count=$(cluster_node_count)
    assert_ge "$count" "3" "Initial: at least 3 nodes"
}

# Scale rejection tests hit the leader directly so the validator returns a proper 4xx.
# Two URL conventions:
#   - docker:  per-node mgmt ports are stacked at ${TARGET_HOST}:${MGMT_PORT}+${i}
#   - cloud:   each node has its own public IP; mgmt port is fixed (CLOUD_MGMT_PORT)
direct_scale_status() {
    local body="$1"
    if [ "${CLOUD_MODE:-false}" = "true" ]; then
        local leader leader_ip
        leader=$(cluster_leader)
        if [ -z "$leader" ] || [ "$leader" = "none" ]; then
            echo "000"
            return 0
        fi
        leader_ip=$(cloud_public_ip "$leader" 2>/dev/null) || { echo "000"; return 0; }
        local port="${CLOUD_MGMT_PORT:-8080}"
        local status
        status=$(http_status "http://${leader_ip}:${port}/api/cluster/scale" \
            -X POST \
            -H "X-API-Key: ${API_KEY}" \
            -H "Content-Type: application/json" \
            -d "$body")
        echo "${status:-000}"
        return 0
    fi
    local base_port="${MGMT_PORT}"
    for i in $(seq 0 $((NODE_COUNT - 1))); do
        local port=$((base_port + i))
        local status
        status=$(http_status "http://${TARGET_HOST}:${port}/api/cluster/scale" \
            -X POST \
            -H "X-API-Key: ${API_KEY}" \
            -H "Content-Type: application/json" \
            -d "$body")
        if [ "$status" != "000" ] && [ -n "$status" ]; then
            echo "$status"
            return 0
        fi
    done
    echo "000"
}

test_reject_scale_to_1() {
    local status
    status=$(direct_scale_status '{"coreCount":1,"expectedVersion":0}')
    if [ "$status" -ge 400 ] 2>/dev/null; then
        log_pass "Scale to 1 rejected (status: ${status})"
        return 0
    fi
    log_fail "Scale to 1 was NOT rejected (status: ${status})"
    return 1
}

test_reject_scale_to_2() {
    local status
    status=$(direct_scale_status '{"coreCount":2,"expectedVersion":0}')
    if [ "$status" -ge 400 ] 2>/dev/null; then
        log_pass "Scale to 2 rejected (status: ${status})"
        return 0
    fi
    log_fail "Scale to 2 was NOT rejected (status: ${status})"
    return 1
}

test_reject_scale_above_max() {
    local status
    status=$(direct_scale_status '{"coreCount":20,"expectedVersion":0}')
    if [ "$status" -ge 400 ] 2>/dev/null; then
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

run_test "Seed cluster config" test_seed_config
run_test "Initial state" test_initial_state
run_test "Reject scale to 1" test_reject_scale_to_1
run_test "Reject scale to 2" test_reject_scale_to_2
run_test "Reject scale above max" test_reject_scale_above_max
run_test "Cluster unchanged" test_cluster_unchanged
print_summary
