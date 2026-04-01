#!/bin/bash
# test-kill-leader.sh — Kill leader node, verify re-election with 4 remaining nodes
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

    # Wait for SWIM failure detection + re-election (~15-20s)
    log_info "Waiting for failure detection and re-election..."
    sleep 20

    # Wait for a NEW leader (not the killed one)
    local new_leader=""
    local elapsed=0
    while [ "$elapsed" -lt 60 ]; do
        new_leader=$(cluster_leader)
        if [ -n "$new_leader" ] && [ "$new_leader" != "$old_leader" ]; then
            break
        fi
        sleep 2
        elapsed=$((elapsed + 2))
    done

    assert_ne "$new_leader" "" "New leader elected: ${new_leader}"
    assert_ne "$new_leader" "$old_leader" "New leader differs from old leader"
}

test_cluster_has_quorum_with_4() {
    local count
    count=$(cluster_node_count)
    assert_eq "$count" "4" "Cluster runs with 4 nodes"
}

test_health_with_4_nodes() {
    local health
    health=$(aether_field health status)
    assert_eq "$health" "healthy" "Cluster healthy with 4 nodes"
}

# Restore cluster for next test suite
cleanup() {
    log_info "Restoring all containers for clean state..."
    restart_all_nodes
    sleep 15
    wait_for_cluster 60 || true
}

run_test "Initial 5 nodes" test_initial_state
run_test "Kill leader and re-elect" test_kill_leader_and_reelect
run_test "Cluster has quorum with 4 nodes" test_cluster_has_quorum_with_4
run_test "Health with 4 nodes" test_health_with_4_nodes
cleanup
print_summary
