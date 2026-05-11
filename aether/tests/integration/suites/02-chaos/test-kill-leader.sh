#!/bin/bash
# test-kill-leader.sh — Kill leader node, verify re-election with 4 remaining nodes
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

test_kill_leader_and_reelect() {
    local old_leader
    old_leader=$(cluster_leader)
    assert_ne "$old_leader" "" "Leader identified: ${old_leader}"

    # Capture topology baseline BEFORE the kill so wait_for_node_departure can
    # scope its event search to the post-kill window.
    local baseline
    baseline=$(topology_now)

    log_info "Killing leader: ${old_leader}"
    kill_node "$old_leader"

    # Pinned MGMT_ENTRY_POINT may have been the leader we just killed — rotate to a
    # surviving core node so CLI/event calls below can reach the cluster.
    rotate_mgmt_entry_point || log_warn "No surviving core node reachable"

    # Event-driven barrier: wait for surviving nodes to actually observe the
    # leader's departure (NODE_LEFT/NODE_FAILED) instead of sleeping. If SWIM
    # detection regresses past 30s this fails fast — the previous `sleep 10`
    # absorbed any such regression silently.
    if ! wait_for_node_departure "$old_leader" "$baseline" 60; then
        log_fail "No NODE_LEFT/NODE_FAILED event for old leader ${old_leader} within 30s"
        return 1
    fi
    log_pass "Departure of ${old_leader} observed via /api/events"

    # Fail-closed: the previous `|| log_warn` demoted a real flake (no leader
    # within 150s) into a passing test by allowing the next assert_ne to read
    # whatever the CLI happened to return.
    if ! wait_for_leader 150; then
        log_fail "Post-kill: no new leader observed within 150s"
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
    # Capture baseline before restart so the event-driven barrier below sees the
    # restart-induced rejoin churn (NODE_JOINED) without racing pre-existing events.
    local cleanup_baseline
    cleanup_baseline=$(topology_now)
    restart_all_nodes
    # Event-driven pre-quiesce barrier: previously a hardcoded `sleep 3` "let
    # reconnection churn start bumping epoch before we ask for quiescence". The
    # author admitted this masks the race — without the sleep, await_generation_quiesced
    # could return OK before churn started. Wait for any surviving node to emit
    # a NODE_JOINED for any peer (post-restart rejoin). On this same baseline
    # the joined-other helper requires excluding a node id; we use the killed
    # leader sentinel so any other rejoin counts.
    if ! wait_for_replacement_of "$(mgmt_entry_point_node)" "$cleanup_baseline" 60; then
        log_warn "No NODE_JOINED rejoin event observed within 60s after restart_all_nodes"
    fi
    await_generation_quiesced "$CLUSTER_ENDPOINT" "current" 180 || \
        log_warn "Cluster did not quiesce after destructive suite; next suite may inherit churn"
    # Fail-loud phase=NORMAL barrier (post-quiesce). Without it, the next chaos
    # test (or 03-scaling's first scale-down) enters a still-BOOTING cluster —
    # SwimProtocol.emitFaultyOrUnknown suppresses NODE_FAILED for any peer not in
    # `everSeenHealthy`, so subsequent kills register as UnknownObserved and the
    # 60s departure-event wait times out across every following destructive test.
    # restart_all_nodes attempts NORMAL but log_warns intentionally (first-restart
    # cold-boot can exceed 180s under cluster A+B load); post-quiesce we have a
    # stronger expectation. If NORMAL hasn't arrived here the cluster is genuinely
    # stuck (CTM breaker tripped without auto-reset firing, split-brain, etc.) and
    # forcing a fail surfaces that root cause instead of cascading into N silent
    # NODE_FAILED-not-observed FAILs downstream.
    if ! wait_for_phase "NORMAL" 300; then
        log_fail "cleanup: cluster phase did not reach NORMAL within 300s. Subsequent destructive tests would fail under SWIM cold-boot suppression — investigate cluster B state (CTM breaker, leader, slot deadlines)."
        return 1
    fi
}

run_test "Initial 5 nodes" test_initial_state

# Pinned-leader skip gate: cluster B's compose file uses `restart: "no"` so
# killing the node bound to the pinned MGMT host port permanently strands every
# subsequent suite. Cluster B has no safe in-test rotation: stop+start on the
# pinned node does not restore the host-mapped port, and disturbing topology by
# killing a non-leader can re-elect the same pinned node again. When leader ==
# pinned at this point, skip the kill-leader scenario (and the dependent quorum
# / health-with-4-nodes / auto-heal asserts) rather than fail or risk a flaky
# pass. The remaining suites still exercise the cluster recovery invariants.
_pinned=$(mgmt_entry_point_node)
# Don't bypass the pinned-leader gate on a CLI hiccup: capture rc and fail
# loudly. Empty string previously masked CLI failure as "no skip needed".
_current_leader=$(cluster_leader 2>/dev/null)
_leader_rc=$?
if [ "$_leader_rc" -ne 0 ] || [ -z "$_current_leader" ]; then
    log_fail "cluster_leader CLI returned rc=${_leader_rc}, leader='${_current_leader}' — cannot evaluate pinned-leader gate"
    print_summary
    exit 1
fi
if [ -n "$_pinned" ] && [ "$_current_leader" = "$_pinned" ]; then
    skip_test "Kill leader and re-elect" \
        "leader '${_current_leader}' is the pinned MGMT entry-point node on cluster ${CLUSTER_ID:-<none>}; no safe in-test rotation on cluster B (restart: no)"
    skip_test "Cluster has quorum" "depends on Kill leader (skipped)"
    skip_test "Health with 4 nodes" "depends on Kill leader (skipped)"
    skip_test "Auto-heal restores to 5" "depends on Kill leader (skipped)"
else
    run_test "Kill leader and re-elect" test_kill_leader_and_reelect
    run_test "Cluster has quorum" test_cluster_has_quorum
    run_test "Health with 4 nodes" test_health_with_4_nodes
    run_test "Auto-heal restores to 5" test_auto_heal
fi
cleanup
print_summary
