#!/bin/bash
# test-deploy-immediate.sh — Immediate deployment via unified deploy command (CDM path)
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "${SCRIPT_DIR}/../../lib/common.sh"
source "${SCRIPT_DIR}/../../lib/cluster.sh"

BLUEPRINT="org.pragmatica.aether.example:url-shortener:1.0.0-alpha"

test_cluster_ready() {
    wait_for_cluster 60
    wait_for_node_count 5 30
}

test_immediate_deploy() {
    local result
    result=$(aether_failover deploy "$BLUEPRINT" --instances 1)
    assert_ne "$result" "" "Immediate deployment returned response"
    log_info "Deploy result: $result"
}

test_cluster_healthy_after_deploy() {
    sleep 10
    assert_cluster_healthy "Cluster healthy after immediate deploy"
}

test_slices_active() {
    wait_for_slices_active 1 60
    local instances
    instances=$(slices_total_instances)
    assert_gt "$instances" "0" "Slices active after immediate deploy"
}

run_test "Cluster ready" test_cluster_ready
run_test "Immediate deploy" test_immediate_deploy
run_test "Cluster healthy" test_cluster_healthy_after_deploy
run_test "Slices active" test_slices_active
print_summary
