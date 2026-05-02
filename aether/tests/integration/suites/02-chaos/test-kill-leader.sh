#!/bin/bash
# test-kill-leader.sh — Kill leader node, verify re-election with 4 remaining nodes
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "${SCRIPT_DIR}/../../lib/common.sh"
source "${SCRIPT_DIR}/../../lib/cluster.sh"

test_initial_state() {
    wait_for_cluster 60
    wait_for_leader 60
    local count
    count=$(cluster_node_count)
    assert_ge "$count" "5" "Initial: at least 5 nodes (${count})"
}

test_kill_leader_and_reelect() {
    local old_leader
    old_leader=$(cluster_leader)
    assert_ne "$old_leader" "" "Leader identified: ${old_leader}"

    # Cluster-B test design assumes leader != pinned MGMT entry point. The
    # entry-point node carries the host-mapped management port that
    # run-tests.sh pins for every cluster-B suite, and cluster B's compose
    # file uses `restart: "no"` so a killed entry point does not come back.
    # If the leader currently sits on the pinned node, kill_node will refuse
    # (correctly) — fail fast here with a clear message rather than letting
    # the harness propagate confusing downstream failures.
    local pinned
    pinned=$(mgmt_entry_point_node)
    if [ -n "$pinned" ] && [ "$old_leader" = "$pinned" ]; then
        log_fail "test-kill-leader: leader '${old_leader}' is the pinned MGMT entry-point node on cluster ${CLUSTER_ID:-<none>}; this test requires leader != entry point. Re-run after the cluster re-elects to a different node, or override MGMT_ENTRY_POINT_NODE."
        return 1
    fi

    log_info "Killing leader: ${old_leader}"
    kill_node "$old_leader"
    # Legitimate chaos window: give SWIM/Rabia failure detection a window to fire.
    sleep 10

    # Pinned MGMT_ENTRY_POINT may have been the leader we just killed — rotate to a
    # surviving core node so CLI calls below can reach the cluster.
    rotate_mgmt_entry_point || log_warn "No surviving core node reachable"

    # Poll for new leader via CLI failover (server-side quiescence may lag without a
    # live leader to advance the snapshot; wait_for_leader polls direct mgmt ports).
    # Rabia re-election can take up to ~60s in adverse timing.
    wait_for_leader 150 || log_warn "Post-kill: no new leader observed within 150s"
    local new_leader
    new_leader=$(cluster_leader)
    assert_ne "$new_leader" "" "New leader elected: ${new_leader}"
    assert_ne "$new_leader" "none" "New leader is not 'none'"
    log_info "Leader after kill: ${new_leader} (was: ${old_leader})"
}

test_cluster_has_quorum() {
    local count
    count=$(cluster_node_count)
    assert_ge "$count" "4" "Cluster has quorum after leader kill (${count} nodes)"
}

test_health_with_4_nodes() {
    local health
    health=$(aether_field health status)
    assert_eq "$health" "healthy" "Cluster healthy with 4 nodes"
}

test_auto_heal() {
    log_info "Waiting for CTM auto-heal to restore cluster to 5 nodes..."
    wait_for_node_count 5 180 || {
        log_fail "Cluster did not reach 5 nodes after auto-heal (current=$(cluster_node_count))"
        return 1
    }
    local count
    count=$(cluster_node_count)
    assert_eq "$count" "5" "Auto-heal restored cluster to exactly 5 nodes"
}

# Restore cluster for next test suite — ClusterGeneration barrier is deterministic.
cleanup() {
    restart_all_nodes
    sleep 3  # let reconnection churn start bumping epoch before we ask for quiescence
    await_generation_quiesced "$CLUSTER_ENDPOINT" "current" 180 || \
        log_warn "Cluster did not quiesce after destructive suite; next suite may inherit churn"
}

run_test "Initial 5 nodes" test_initial_state
run_test "Kill leader and re-elect" test_kill_leader_and_reelect
run_test "Cluster has quorum" test_cluster_has_quorum
run_test "Health with 4 nodes" test_health_with_4_nodes
run_test "Auto-heal restores to 5" test_auto_heal
cleanup
print_summary
