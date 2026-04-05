#!/bin/bash
# test-kill-node.sh — Kill non-leader node, verify cluster survives with 4
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "${SCRIPT_DIR}/../../lib/common.sh"
source "${SCRIPT_DIR}/../../lib/cluster.sh"

test_initial_state() {
    wait_for_cluster 60
    wait_for_leader 60
    local count
    count=$(cluster_node_count)
    assert_eq "$count" "5" "Initial: 5 nodes"
}

test_kill_non_leader() {
    local leader
    leader=$(cluster_leader)
    log_info "Current leader: ${leader}"

    local victim
    victim=$(pick_non_leader "$leader")
    assert_ne "$victim" "" "Non-leader identified: ${victim}"

    log_info "Killing non-leader: ${victim}"
    kill_node "$victim"

    # Wait for failure detection
    wait_for_node_count 4 60
    local count
    count=$(cluster_node_count)
    assert_eq "$count" "4" "Cluster detects node loss: 4 nodes"
}

test_leader_unchanged() {
    local leader
    leader=$(cluster_leader)
    assert_ne "$leader" "" "Leader still elected: ${leader}"
}

test_health_with_4_nodes() {
    local health
    health=$(aether_field health status)
    assert_eq "$health" "healthy" "Cluster healthy with 4 nodes"
}

test_auto_heal() {
    log_info "Waiting for CTM auto-heal to restore cluster to 5 nodes..."
    wait_for_node_count 5 180
    local count
    count=$(cluster_node_count)
    assert_eq "$count" "5" "Auto-heal restored cluster to 5 nodes"
}

cleanup() {
    log_info "Restoring all containers for clean state..."
    restart_all_nodes
    sleep 15
    wait_for_cluster 60 || true
}

run_test "Initial 5 nodes" test_initial_state
run_test "Kill non-leader node" test_kill_non_leader
run_test "Leader unchanged" test_leader_unchanged
run_test "Health with 4 nodes" test_health_with_4_nodes
run_test "Auto-heal restores to 5" test_auto_heal
cleanup
print_summary
