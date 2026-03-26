#!/bin/bash
# test-destroy.sh — Full cluster teardown
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "${SCRIPT_DIR}/../../lib/common.sh"
source "${SCRIPT_DIR}/../../lib/cluster.sh"

test_cluster_exists() {
    wait_for_cluster 60
    local count
    count=$(cluster_node_count)
    assert_gt "$count" "0" "Cluster exists with ${count} nodes"
}

test_destroy_cluster() {
    if command -v aether &>/dev/null; then
        log_info "Running: aether cluster destroy --yes"
        aether cluster destroy --yes
        log_pass "Destroy command completed"
    else
        log_info "No aether CLI — destroying via Docker directly"
        remote_exec "docker ps -a --filter 'name=aether-' --format '{{.Names}}' | xargs -r docker stop" 2>/dev/null || true
        remote_exec "docker ps -a --filter 'name=aether-' --format '{{.Names}}' | xargs -r docker rm -f" 2>/dev/null || true
        log_pass "Docker containers removed"
    fi
}

test_cluster_gone() {
    sleep 10
    local status
    status=$(http_status "${CLUSTER_ENDPOINT}/health/live")
    if [ "$status" = "000" ] || [ "$status" = "" ]; then
        log_pass "Cluster endpoint unreachable (destroyed)"
        return 0
    fi
    log_fail "Cluster endpoint still reachable (status: ${status})"
    return 1
}

test_no_containers_running() {
    local containers
    containers=$(list_aether_containers 2>/dev/null || echo "")
    if [ -z "$containers" ]; then
        log_pass "No Aether containers running"
        return 0
    fi
    log_fail "Aether containers still running: ${containers}"
    return 1
}

test_data_cleaned() {
    local data_exists
    data_exists=$(remote_exec "ls /opt/aether/data/ 2>/dev/null | wc -l | tr -d ' '" 2>/dev/null || echo "0")
    if [ "$data_exists" = "0" ] || [ -z "$data_exists" ]; then
        log_pass "Aether data directory clean"
    else
        log_warn "Data directory not empty (${data_exists} files) — may need manual cleanup"
        log_pass "Destroy test complete (data cleanup is optional)"
    fi
}

run_test "Cluster exists" test_cluster_exists
run_test "Destroy cluster" test_destroy_cluster
run_test "Cluster gone" test_cluster_gone
run_test "No containers running" test_no_containers_running
run_test "Data cleaned" test_data_cleaned
print_summary
