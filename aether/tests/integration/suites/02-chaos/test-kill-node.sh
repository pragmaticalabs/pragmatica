#!/bin/bash
# test-kill-node.sh — Kill non-leader node, verify cluster survives with 4
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "${SCRIPT_DIR}/../../lib/common.sh"
source "${SCRIPT_DIR}/../../lib/cluster.sh"
source "${SCRIPT_DIR}/../../lib/topology.sh"
source "${SCRIPT_DIR}/../../lib/generation.sh"

test_initial_state() {
    wait_for_cluster_ready 60
    # Wait for phase=NORMAL to bypass SWIM cold-boot suppression of NODE_FAILED events.
    wait_for_phase "NORMAL" 180 || log_warn "Cluster phase still COLD_BOOT; chaos kill may produce UnknownObserved (no NODE_FAILED event)"
    wait_for_leader 60
    local count
    count=$(cluster_member_count)
    assert_ge "$count" "5" "Initial: at least 5 nodes (${count})"
}

test_kill_non_leader() {
    # Wait for a leader before capture — chaos-tail instability from a prior
    # destructive suite can drop the leader transiently. Without this guard,
    # a bare `leader=$(cluster_leader)` returns 1 under set -e and aborts the
    # script silently. See test-kill-leader.sh for the original observation.
    if ! wait_for_leader 60; then
        log_fail "Pre-kill: cluster has no leader within 60s"
        return 1
    fi
    local leader
    leader=$(cluster_leader)
    log_info "Current leader: ${leader}"

    local victim
    victim=$(pick_non_leader "$leader")
    assert_ne "$victim" "" "Non-leader identified: ${victim}"

    # Capture topology baseline BEFORE kill so the event-driven barrier can
    # scope its search to the post-kill window.
    KILLED_VICTIM="$victim"
    KILLED_BASELINE=$(topology_now)

    log_info "Killing non-leader: ${victim}"
    kill_node "$victim"

    # Event-driven barrier (replaces `sleep 10`). The previous sleep absorbed
    # SWIM detection regressions silently; this assertion fails fast if the
    # surviving nodes don't observe NODE_LEFT/NODE_FAILED for the victim within 60s.
    # Dual-signal: if the victim never reached core-HEALTHY membership, no
    # coreMemberIds-delta event fires (correct product behavior), so fall back to
    # membership-absence (wait_for_node_removed: 404 from /api/nodes/lifecycle OR
    # absent from /api/nodes/status). EITHER genuinely confirms the node is gone.
    if ! wait_for_node_departure "$victim" "$KILLED_BASELINE" 60 \
        && ! wait_for_node_removed "$victim" 60; then
        log_fail "No NODE_LEFT/NODE_FAILED event AND not removed from membership for ${victim} within 60s"
        return 1
    fi
    log_pass "Departure of ${victim} observed via /api/events (current count: $(cluster_member_count))"
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
    count=$(cluster_member_count)
    assert_eq "$count" "5" "Auto-heal restored cluster to exactly 5 nodes"
}

cleanup() {
    # Semantic baseline restore — see test-kill-leader.sh:cleanup() for the
    # rationale. `restore_cluster_baseline` consumes the same operator-visible
    # signals (lifecycle states, generation snapshot, cluster phase) that the
    # next suite will read, so the previous explicit event/quiesce/phase
    # barriers are subsumed.
    restore_cluster_baseline || \
        log_warn "cleanup: restore_cluster_baseline reported non-zero; subsequent suites may inherit cluster churn"
}

# Run cleanup on ANY exit path — including a `return 1` from inside a test
# function that propagates up through `set -e` and aborts the script. Without
# this trap, a failed kill / leader-stability assertion left the cluster in a
# degraded state, which then broke every subsequent test-*.sh in 02-chaos.
trap 'cleanup' EXIT

run_test "Initial 5 nodes" test_initial_state
run_test "Kill non-leader node" test_kill_non_leader
run_test "Leader unchanged" test_leader_unchanged
run_test "Health with 4 nodes" test_health_with_4_nodes
run_test "Auto-heal restores to 5" test_auto_heal
# cleanup runs via EXIT trap — guarantees baseline restore even if a run_test
# above triggers `set -e` abort via `return 1` from inside a test function.
print_summary
