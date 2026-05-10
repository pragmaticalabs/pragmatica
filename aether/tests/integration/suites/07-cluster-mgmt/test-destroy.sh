#!/bin/bash
# test-destroy.sh — Full cluster teardown
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "${SCRIPT_DIR}/../../lib/common.sh"
source "${SCRIPT_DIR}/../../lib/cluster.sh"

test_destroy_guard() {
    if [ "${ALLOW_DESTROY:-false}" != "true" ]; then
        skip_test "Cluster destroy" "Set ALLOW_DESTROY=true to run"
        print_summary
        exit 0
    fi
    log_pass "Destroy guard passed (ALLOW_DESTROY=true)"
}

test_cluster_exists() {
    wait_for_cluster 60
    local count
    count=$(cluster_node_count)
    assert_gt "$count" "0" "Cluster exists with ${count} nodes"
}

test_destroy_cluster() {
    if command -v aether &>/dev/null; then
        log_info "Running: aether cluster destroy --yes"
        if ! aether cluster destroy --yes; then
            log_fail "aether cluster destroy --yes failed"
            return 1
        fi
        log_pass "Destroy command completed"
    else
        log_info "No aether CLI — destroying via Docker directly"
        # Don't `2>/dev/null || true` — that hides stderr AND ignores rc, so a
        # remote_exec failure would be invisible. Capture stderr, log on failure.
        local stop_err rm_err stop_rc rm_rc
        stop_err=$(remote_exec "docker ps -a --filter 'name=aether-' --format '{{.Names}}' | xargs -r docker stop" 2>&1)
        stop_rc=$?
        if [ "$stop_rc" -ne 0 ]; then
            log_warn "docker stop returned rc=${stop_rc}: $(printf '%s' "$stop_err" | head -c 300)"
        fi
        rm_err=$(remote_exec "docker ps -a --filter 'name=aether-' --format '{{.Names}}' | xargs -r docker rm -f" 2>&1)
        rm_rc=$?
        if [ "$rm_rc" -ne 0 ]; then
            log_fail "docker rm -f returned rc=${rm_rc}: $(printf '%s' "$rm_err" | head -c 300)"
            return 1
        fi
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
    # Don't `|| echo "0"` — that defaults to the success path on ANY error
    # (network, ssh, missing helper). Use a sentinel so the assertion fails
    # loudly on infra trouble instead of silently passing.
    local data_exists data_rc
    data_exists=$(remote_exec "ls \$HOME/aether/data/ 2>/dev/null | wc -l | tr -d ' '")
    data_rc=$?
    if [ "$data_rc" -ne 0 ]; then
        log_fail "remote_exec ls failed (rc=${data_rc}): cannot verify data cleanup"
        return 1
    fi
    if [ -z "$data_exists" ]; then
        log_fail "remote_exec ls returned empty output: cannot verify data cleanup"
        return 1
    fi
    if [ "$data_exists" = "0" ]; then
        log_pass "Aether data directory clean"
    else
        log_warn "Data directory not empty (${data_exists} files) — may need manual cleanup"
        log_pass "Destroy test complete (data cleanup is optional)"
    fi
}

run_test "Destroy guard" test_destroy_guard
run_test "Cluster exists" test_cluster_exists
run_test "Destroy cluster" test_destroy_cluster
run_test "Cluster gone" test_cluster_gone
run_test "No containers running" test_no_containers_running
run_test "Data cleaned" test_data_cleaned
print_summary
