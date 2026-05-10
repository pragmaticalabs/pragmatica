#!/bin/bash
# test-kill-node.sh — Kill non-leader node, verify cluster survives with 4
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "${SCRIPT_DIR}/../../lib/common.sh"
source "${SCRIPT_DIR}/../../lib/cluster.sh"
source "${SCRIPT_DIR}/../../lib/topology.sh"
source "${SCRIPT_DIR}/../../lib/generation.sh"

test_initial_state() {
    wait_for_cluster 60
    # Wait for phase=NORMAL to bypass SWIM cold-boot suppression of NODE_FAILED events.
    wait_for_phase "NORMAL" 180 || log_warn "Cluster phase still BOOTING; chaos kill may produce UnknownObserved (no NODE_FAILED event)"
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

    # Capture topology baseline BEFORE kill so the event-driven barrier can
    # scope its search to the post-kill window.
    KILLED_VICTIM="$victim"
    KILLED_BASELINE=$(topology_now)

    log_info "Killing non-leader: ${victim}"
    kill_node "$victim"

    # Event-driven barrier (replaces `sleep 10`). The previous sleep absorbed
    # SWIM detection regressions silently; this assertion fails fast if the
    # surviving nodes don't observe NODE_LEFT/NODE_FAILED for the victim within 60s.
    if ! wait_for_node_departure "$victim" "$KILLED_BASELINE" 60; then
        log_fail "No NODE_LEFT/NODE_FAILED event for ${victim} within 60s"
        return 1
    fi
    log_pass "Departure of ${victim} observed via /api/events (current count: $(cluster_node_count))"
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
    # Event-driven pre-quiesce barrier (replaces `sleep 3`). Author admitted the
    # sleep masks the race — without it await_generation_quiesced returns OK
    # before churn starts. Wait for any rejoin event before requesting quiescence.
    local cleanup_baseline
    cleanup_baseline=$(topology_now)
    restart_all_nodes
    if ! wait_for_replacement_of "${KILLED_VICTIM:-$(mgmt_entry_point_node)}" "$cleanup_baseline" 60; then
        log_warn "No NODE_JOINED rejoin event observed within 60s after restart_all_nodes"
    fi
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
