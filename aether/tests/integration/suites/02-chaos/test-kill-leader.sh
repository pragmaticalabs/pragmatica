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
    # Phase=NORMAL barrier (post-quiesce). Without it, the next chaos test (or
    # 03-scaling's first scale-down) inherits a still-BOOTING cluster —
    # SwimProtocol.emitFaultyOrUnknown suppresses NODE_FAILED for any peer not in
    # `everSeenHealthy`, so subsequent kills register as UnknownObserved and
    # downstream tests time out at the 60s departure-event wait. Soft on docker-
    # remote (log_warn): cluster B compose-restart genuinely cannot reach NORMAL
    # within a hard budget — fail-loud here cascades a single test-infra slowness
    # into 5+ downstream cluster B test failures. Subsequent tests already use
    # their own `wait_for_phase` warns; the suite tally pinpoints the issue.
    wait_for_phase "NORMAL" 300 || \
        log_warn "cleanup: cluster phase did not reach NORMAL within 300s; subsequent destructive tests may surface SWIM cold-boot suppression as opaque NODE_FAILED-not-observed failures"
}

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
cleanup
print_summary
