#!/bin/bash
# test-kill-multiple.sh — Kill 2 nodes, verify cluster survives with 3 (quorum)
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

test_kill_two_nodes() {
    local leader
    leader=$(cluster_leader)
    local victims
    victims=$(pick_non_leader "$leader" 2)
    local victim1 victim2
    victim1=$(echo "$victims" | head -1)
    victim2=$(echo "$victims" | tail -1)

    log_info "Killing node 1: ${victim1}"
    kill_node "$victim1"
    sleep 5
    log_info "Killing node 2: ${victim2}"
    kill_node "$victim2"

    # Wait for failure detection (auto-heal may restore before we observe 3)
    sleep 15
    local count
    count=$(cluster_node_count)
    assert_ge "$count" "3" "Cluster survives with quorum after 2 kills (${count} nodes)"
}

test_quorum_maintained() {
    local health
    health=$(aether_field health status)
    assert_eq "$health" "healthy" "Cluster healthy after 2 kills"
}

test_leader_still_active() {
    local leader
    leader=$(cluster_leader)
    assert_ne "$leader" "" "Leader active with 3 nodes: ${leader}"
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
    wait_for_node_count 5 120 || true
    wait_for_leader 60 || true
}

run_test "Initial 5 nodes" test_initial_state
run_test "Kill 2 nodes" test_kill_two_nodes
run_test "Quorum maintained with 3" test_quorum_maintained
run_test "Leader still active" test_leader_still_active
run_test "Auto-heal restores to 5" test_auto_heal
cleanup
print_summary
