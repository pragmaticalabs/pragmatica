#!/bin/bash
# test-kill-multiple.sh — Kill 2 nodes, verify cluster survives with 3 (quorum)
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

test_kill_two_nodes() {
    # Wait for a leader before capture — chaos-tail instability can drop the
    # leader transiently between test_initial_state and this function. A bare
    # `leader=$(cluster_leader)` would return 1 under set -e and abort the
    # script silently. See test-kill-leader.sh for the original observation.
    if ! wait_for_leader 60; then
        log_fail "Pre-kill: cluster has no leader within 60s"
        return 1
    fi
    local leader
    leader=$(cluster_leader)
    local victims
    victims=$(pick_non_leader "$leader" 2)
    local victim1 victim2
    victim1=$(echo "$victims" | head -1)
    victim2=$(echo "$victims" | tail -1)

    # Capture topology baseline before any kills so the event-driven barriers
    # below can scope their searches to the post-kill window.
    local baseline
    baseline=$(topology_now)
    KILLED_VICTIM1="$victim1"
    KILLED_VICTIM2="$victim2"

    log_info "Killing node 1: ${victim1}"
    kill_node "$victim1"

    # Event-driven barrier between the two kills (replaces `sleep 5`). Emulates
    # staggered failure where the second kill happens AFTER SWIM has actually
    # detected the first — fails fast if SWIM regresses past the budget.
    # Dual-signal: if victim1 never reached core-HEALTHY membership, no
    # coreMemberIds-delta event fires (correct product behavior), so fall back to
    # membership-absence (wait_for_node_removed: 404 OR absent from status).
    if ! wait_for_node_departure "$victim1" "$baseline" 90 \
        && ! wait_for_node_removed "$victim1" 90; then
        log_fail "No NODE_LEFT/NODE_FAILED event AND not removed from membership for first victim ${victim1} within 90s"
        return 1
    fi
    log_pass "Departure of ${victim1} observed"

    log_info "Killing node 2: ${victim2}"
    kill_node "$victim2"
    # Dual-signal departure for victim2 — same rationale as victim1 above.
    if ! wait_for_node_departure "$victim2" "$baseline" 90 \
        && ! wait_for_node_removed "$victim2" 90; then
        log_fail "No NODE_LEFT/NODE_FAILED event AND not removed from membership for second victim ${victim2} within 90s"
        return 1
    fi
    log_pass "Departure of ${victim2} observed"

    # ClusterGeneration commits the post-kill membership view. After quiescence
    # the count reflects whatever CTM has done — just assert quorum.
    wait_for_node_count 5 240 || \
        log_warn "Post-kill quiescence not observed within 240s"
    local count
    count=$(cluster_member_count)
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
    log_info "Waiting for CTM auto-heal to quiesce at the post-replacement generation..."
    wait_for_node_count 5 240 || {
        log_fail "Cluster did not quiesce after auto-heal"
        return 1
    }
    local count
    count=$(cluster_member_count)
    assert_eq "$count" "5" "Auto-heal restored cluster to exactly 5 nodes"
}

cleanup() {
    # Semantic baseline restore — see test-kill-leader.sh:cleanup() for the
    # rationale. `restore_cluster_baseline` re-enables auto-heal, resets the
    # CTM circuit breaker, scales to NODE_COUNT, then waits for N ON_DUTY
    # healthy cores + generation quiescence + soft phase=NORMAL.
    restore_cluster_baseline || \
        log_warn "cleanup: restore_cluster_baseline reported non-zero; subsequent suites may inherit cluster churn"
}

# Run cleanup on ANY exit path — including a `return 1` from inside a test
# function that propagates up through `set -e` and aborts the script. Without
# this trap, a failed kill / quorum assertion left the cluster in a degraded
# state, which then broke every subsequent test-*.sh in 02-chaos (observed
# 2026-05-22: 0p/6f cascade).
trap 'cleanup' EXIT

run_test "Initial 5 nodes" test_initial_state
run_test "Kill 2 nodes" test_kill_two_nodes
run_test "Quorum maintained with 3" test_quorum_maintained
run_test "Leader still active" test_leader_still_active
run_test "Auto-heal restores to 5" test_auto_heal
# cleanup runs via EXIT trap — guarantees baseline restore even if a run_test
# above triggers `set -e` abort via `return 1` from inside a test function.
print_summary
