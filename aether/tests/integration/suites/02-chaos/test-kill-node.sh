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
    assert_ge "$count" "5" "Initial: at least 5 nodes (${count})"
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

    # Give auto-heal a chance to observe the failure. We don't assert a specific
    # intermediate count because CTM reconciliation may have already restored or
    # even transiently provisioned extra nodes — the only invariants that matter
    # are covered by subsequent tests (leader stable, cluster healthy, back to 5).
    sleep 10
    log_pass "Kill observed (current count: $(cluster_node_count))"
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
    log_info "Waiting for CTM auto-heal to quiesce at the post-kill generation..."
    # ClusterGeneration barrier: the post-kill generation commits once replacement
    # is in place and membership is stable. No 5..7 tolerance window needed.
    wait_for_node_count 5 180 || {
        log_fail "Cluster did not quiesce after auto-heal"
        return 1
    }
    local count
    count=$(cluster_node_count)
    assert_eq "$count" "5" "Auto-heal restored cluster to exactly 5 nodes"
}

cleanup() {
    restart_all_nodes
    sleep 3  # let reconnection churn start bumping epoch before we ask for quiescence
    await_generation_quiesced "$CLUSTER_ENDPOINT" "current" 180 || \
        log_warn "Cluster did not quiesce after destructive suite; next suite may inherit churn"
}

run_test "Initial 5 nodes" test_initial_state
run_test "Kill non-leader node" test_kill_non_leader
run_test "Leader unchanged" test_leader_unchanged
run_test "Health with 4 nodes" test_health_with_4_nodes
run_test "Auto-heal restores to 5" test_auto_heal
cleanup
print_summary
