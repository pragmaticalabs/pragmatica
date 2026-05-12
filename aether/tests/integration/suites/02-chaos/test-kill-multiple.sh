#!/bin/bash
# test-kill-multiple.sh — Kill 2 nodes, verify cluster survives with 3 (quorum)
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "${SCRIPT_DIR}/../../lib/common.sh"
source "${SCRIPT_DIR}/../../lib/cluster.sh"
source "${SCRIPT_DIR}/../../lib/topology.sh"
source "${SCRIPT_DIR}/../../lib/generation.sh"

test_initial_state() {
    wait_for_cluster 60
    # Wait for phase=NORMAL to bypass SWIM cold-boot suppression of NODE_FAILED events.
    wait_for_phase "NORMAL" 180 || log_warn "Cluster phase still COLD_BOOT; chaos kill may produce UnknownObserved (no NODE_FAILED event)"
    wait_for_leader 60
    local count
    count=$(cluster_node_count)
    assert_ge "$count" "5" "Initial: at least 5 nodes (${count})"
}

test_kill_two_nodes() {
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
    if ! wait_for_node_departure "$victim1" "$baseline" 90; then
        log_fail "No NODE_LEFT/NODE_FAILED event for first victim ${victim1} within 90s"
        return 1
    fi
    log_pass "Departure of ${victim1} observed"

    log_info "Killing node 2: ${victim2}"
    kill_node "$victim2"
    if ! wait_for_node_departure "$victim2" "$baseline" 90; then
        log_fail "No NODE_LEFT/NODE_FAILED event for second victim ${victim2} within 90s"
        return 1
    fi
    log_pass "Departure of ${victim2} observed"

    # ClusterGeneration commits the post-kill membership view. After quiescence
    # the count reflects whatever CTM has done — just assert quorum.
    wait_for_node_count 5 240 || \
        log_warn "Post-kill quiescence not observed within 240s"
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
    log_info "Waiting for CTM auto-heal to quiesce at the post-replacement generation..."
    wait_for_node_count 5 240 || {
        log_fail "Cluster did not quiesce after auto-heal"
        return 1
    }
    local count
    count=$(cluster_node_count)
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

run_test "Initial 5 nodes" test_initial_state
run_test "Kill 2 nodes" test_kill_two_nodes
run_test "Quorum maintained with 3" test_quorum_maintained
run_test "Leader still active" test_leader_still_active
run_test "Auto-heal restores to 5" test_auto_heal
cleanup
print_summary
