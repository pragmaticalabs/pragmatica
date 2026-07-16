#!/bin/bash
# test-kill-leader.sh — Kill leader node, verify re-election with 4 remaining nodes
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "${SCRIPT_DIR}/../../lib/common.sh"
source "${SCRIPT_DIR}/../../lib/cluster.sh"
source "${SCRIPT_DIR}/../../lib/topology.sh"
source "${SCRIPT_DIR}/../../lib/generation.sh"

test_initial_state() {
    wait_for_cluster_ready 60
    # Gate on phase=NORMAL so the cluster is fully formed (leader + quorum)
    # before the chaos kill — avoids killing during cold-boot churn.
    wait_for_phase "NORMAL" 180 || log_warn "Cluster phase still COLD_BOOT before chaos kill"
    wait_for_leader 60
    # Poll-with-settle (up to 60s): the prior suite's restore gates on leader
    # deficit=0 which can precede generation snapshot convergence by up to one
    # reconciler tick while a CTM replacement finalises admission.
    wait_for "Initial: at least 5 nodes" '[ "$(cluster_member_count)" -ge 5 ]' 60 || true
    local count
    count=$(cluster_member_count)
    assert_ge "$count" "5" "Initial: at least 5 nodes (${count})"
}

test_kill_leader_and_reelect() {
    # Wait for a leader before capture — `test_initial_state` confirmed one
    # moments ago, but the cluster B chaos-tail (prior test-joining-window-kill
    # priming + replacement kill) can momentarily lose the leader. Without this
    # barrier, a transient empty `cluster_leader` returns 1 under set -e and
    # aborts the script silently after the STEP header is printed but before
    # any [PASS]/[FAIL] line — observed 2026-05-22.
    if ! wait_for_leader 60; then
        log_fail "Pre-kill: cluster has no leader within 60s (chaos-tail instability)"
        return 1
    fi
    local old_leader
    old_leader=$(cluster_leader)
    assert_ne "$old_leader" "" "Leader identified: ${old_leader}"

    log_info "Killing leader: ${old_leader}"
    kill_node "$old_leader"

    # Pinned MGMT_ENTRY_POINT may have been the leader we just killed — rotate to a
    # surviving core node so CLI/event calls below can reach the cluster.
    rotate_mgmt_entry_point || log_warn "No surviving core node reachable"

    # v2 removal barrier: a killed node simply leaves the SWIM-fed membership —
    # there is NO NODE_LEFT/NODE_FAILED domain event in v2 (the aggregator's
    # onMembershipDecision is a no-op). The authoritative signal is membership
    # absence: /api/nodes/lifecycle/<id> 404 OR drop-out of /api/nodes/status
    # cluster.nodes[]. Fails fast if removal regresses past the budget.
    if ! wait_for_node_removed "$old_leader" 90; then
        log_fail "Old leader ${old_leader} still present in membership after 90s"
        return 1
    fi
    log_pass "Old leader ${old_leader} removed from membership"

    # Fail-closed: the previous `|| log_warn` demoted a real flake (no leader
    # within 150s) into a passing test by allowing the next assert_ne to read
    # whatever the CLI happened to return.
    #
    # Use wait_for_leader_change rather than bare wait_for_leader: after the
    # kill, surviving nodes' leader projection may briefly still echo the
    # killed NodeId until the next consensus round publishes a new one. The
    # bare predicate would return immediately with the stale value and trip
    # the `new_leader != old_leader` assertion below for the wrong reason.
    if ! wait_for_leader_change "$old_leader" 150; then
        log_fail "Post-kill: no new leader (different from ${old_leader}) observed within 150s"
        return 1
    fi
    local new_leader
    new_leader=$(cluster_leader)
    assert_ne "$new_leader" "" "New leader elected: ${new_leader}"
    assert_ne "$new_leader" "none" "New leader is not 'none'"
    assert_ne "$new_leader" "$old_leader" "New leader ${new_leader} differs from killed leader ${old_leader}"
    log_info "Leader after kill: ${new_leader} (was: ${old_leader})"
}

test_cluster_has_quorum() {
    local count
    count=$(cluster_member_count)
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
        log_fail "Cluster did not reach 5 nodes after auto-heal (current=$(cluster_member_count))"
        return 1
    }
    local count
    count=$(cluster_member_count)
    assert_eq "$count" "5" "Auto-heal restored cluster to exactly 5 nodes"
}

# Restore cluster for next test suite via semantic baseline restore.
# `restore_cluster_baseline` re-enables auto-heal, resets the CTM circuit
# breaker, reactivates any DRAINING nodes, scales to NODE_COUNT, then waits
# for N ON_DUTY healthy cores + generation quiescence + soft phase=NORMAL.
# This replaces the old fixed-fixture `restart_all_nodes` flow which forced
# the original 5 compose containers back and fought CTM auto-heal.
cleanup() {
    restore_cluster_baseline || \
        log_warn "cleanup: restore_cluster_baseline reported non-zero; subsequent suites may inherit cluster churn"
}

# Run cleanup on ANY exit path — including a `return 1` from inside a test
# function that propagates up through `set -e` and aborts the script. Without
# this trap, a failed kill / re-election timing assertion left the cluster in
# a degraded state, which then broke every subsequent test-*.sh in 02-chaos
# (observed 2026-05-22: 0p/6f cascade originating here).
trap 'cleanup' EXIT

run_test "Initial 5 nodes" test_initial_state

# Pinned-leader skip gate REMOVED: cluster B now runs an nginx mgmt sidecar
# (aether-b-mgmt-gateway) that owns MGMT_ENTRY_POINT host port 5160 and
# round-robins /api requests across all 5 cores via proxy_next_upstream. The
# gateway survives any single-core failure, so killing the leader -- even when
# it's the node that was historically pinned as the operator entry point --
# no longer strands the harness. All four downstream scenarios are exercised
# unconditionally.
#
# If a future cloud-env or other deployment lacks the gateway, set
# MGMT_ENTRY_POINT_NODE to re-introduce a pinning constraint; mgmt_entry_point_node()
# will surface the override and the historical gate can be reinstated locally.
run_test "Kill leader and re-elect" test_kill_leader_and_reelect
run_test "Cluster has quorum" test_cluster_has_quorum
run_test "Health with 4 nodes" test_health_with_4_nodes
run_test "Auto-heal restores to 5" test_auto_heal
# cleanup runs via EXIT trap — guarantees baseline restore even if a run_test
# above triggers `set -e` abort via `return 1` from inside a test function.
print_summary
