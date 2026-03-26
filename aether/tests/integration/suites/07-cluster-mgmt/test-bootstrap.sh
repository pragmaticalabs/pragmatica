#!/bin/bash
# test-bootstrap.sh — aether cluster bootstrap, verify formation
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "${SCRIPT_DIR}/../../lib/common.sh"
source "${SCRIPT_DIR}/../../lib/cluster.sh"

CONFIG_FILE="${SCRIPT_DIR}/../../cluster-config.toml"

test_config_exists() {
    if [ -f "$CONFIG_FILE" ]; then
        log_pass "Cluster config exists: ${CONFIG_FILE}"
        return 0
    fi
    log_fail "Cluster config not found: ${CONFIG_FILE}"
    return 1
}

test_bootstrap_cluster() {
    if command -v aether &>/dev/null; then
        log_info "Running: aether cluster bootstrap"
        aether cluster bootstrap "$CONFIG_FILE" --yes
        log_pass "Bootstrap command completed"
    else
        log_warn "aether CLI not in PATH — assuming cluster already bootstrapped"
        log_pass "Bootstrap skipped (no CLI)"
    fi
}

test_cluster_forms() {
    wait_for_cluster 180
    log_pass "Cluster healthy after bootstrap"
}

test_expected_node_count() {
    wait_for_node_count 5 120
    local count
    count=$(cluster_node_count)
    assert_eq "$count" "5" "Expected 5 nodes after bootstrap"
}

test_leader_elected() {
    wait_for_leader 60
    local leader
    leader=$(cluster_leader)
    assert_ne "$leader" "" "Leader elected: ${leader}"
}

test_health_probes() {
    assert_http_status "${CLUSTER_ENDPOINT}/health/live" "200" "Liveness probe after bootstrap"
    assert_http_status "${CLUSTER_ENDPOINT}/health/ready" "200" "Readiness probe after bootstrap"
}

test_management_api_accessible() {
    local status
    status=$(api_get "/api/status")
    assert_ne "$status" "" "Management API returns status"
}

run_test "Config exists" test_config_exists
run_test "Bootstrap cluster" test_bootstrap_cluster
run_test "Cluster forms" test_cluster_forms
run_test "Expected node count" test_expected_node_count
run_test "Leader elected" test_leader_elected
run_test "Health probes" test_health_probes
run_test "Management API accessible" test_management_api_accessible
print_summary
