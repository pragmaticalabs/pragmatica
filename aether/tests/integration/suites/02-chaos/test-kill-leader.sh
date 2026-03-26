#!/bin/bash
# test-kill-leader.sh — Kill leader node, verify re-election and recovery
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "${SCRIPT_DIR}/../../lib/common.sh"
source "${SCRIPT_DIR}/../../lib/cluster.sh"

test_initial_state() {
    wait_for_cluster 60
    local count
    count=$(cluster_node_count)
    assert_eq "$count" "5" "Initial: 5 nodes"
}

test_kill_leader_and_reelect() {
    local old_leader
    old_leader=$(cluster_leader)
    assert_ne "$old_leader" "" "Leader identified: ${old_leader}"

    log_info "Killing leader: ${old_leader}"
    kill_node "$old_leader"

    # Wait for new leader to be elected
    sleep 10
    wait_for_leader 60

    local new_leader
    new_leader=$(cluster_leader)
    assert_ne "$new_leader" "" "New leader elected: ${new_leader}"
    assert_ne "$new_leader" "$old_leader" "New leader differs from old leader"
}

test_cluster_recovers_to_5() {
    log_info "Waiting for auto-heal to restore node count"
    wait_for_node_count 5 180
    local count
    count=$(cluster_node_count)
    assert_eq "$count" "5" "Cluster recovered to 5 nodes"
}

test_slices_redistributed() {
    wait_for_slices_active 1 60
    local instances
    instances=$(slices_total_instances)
    assert_gt "$instances" "0" "Slices redistributed: ${instances} instances"
}

test_health_restored() {
    assert_http_status "${CLUSTER_ENDPOINT}/health/ready" "200" "Cluster healthy after leader kill"
}

run_test "Initial 5 nodes" test_initial_state
run_test "Kill leader and re-elect" test_kill_leader_and_reelect
run_test "Cluster recovers to 5 nodes" test_cluster_recovers_to_5
run_test "Slices redistributed" test_slices_redistributed
run_test "Health restored" test_health_restored
print_summary
