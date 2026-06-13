#!/bin/bash
# test-quic-connectivity.sh — Verify QUIC connections, kill a follower,
# verify the cluster observed departure + replacement via events,
# and that quorum was never broken during the window.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "${SCRIPT_DIR}/../../lib/common.sh"
source "${SCRIPT_DIR}/../../lib/cluster.sh"
source "${SCRIPT_DIR}/../../lib/topology.sh"

test_cluster_ready() {
    wait_for_cluster_ready 60
    # SWIM cold-boot suppression bypass: kills against a phase=COLD_BOOT cluster
    # produce UnknownObserved (not FaultyObserved), so no NODE_FAILED event fires.
    # Soft (log_warn): cluster B on docker-remote can be slow to reach NORMAL
    # after a destructive predecessor suite, and a fail-fast here cascades the
    # cumulative degradation issue (already surfaced as a log_warn in 02-chaos
    # cleanup) into a 12-network suite failure.
    wait_for_phase "NORMAL" 180 || \
        log_warn "Cluster phase did not reach NORMAL within 180s — kills below may be silently absorbed by SWIM cold-boot suppression"
    log_pass "Cluster ready"
}

test_all_nodes_connected() {
    # Reads the queried node's `connectedPeerCount` from /api/cluster/topology.
    # ClusterTopologyRoutes.assembleFromTopologyManager populates this from
    # node.connectedPeerIds().size() — the count of QUIC-connected peers (excluding
    # self). For a healthy 5-node cluster each node sees 4 peers.
    #
    # SETTLED-state wait, not an instant read: a single snapshot races transient
    # reconnect blips (count dips to 3 for a moment mid-churn after the preceding
    # suites' kills — the known connectedPeerCount=2/3 transient; settled state is 4).
    # The bounded wait still catches a systemic mesh defect (a real loss never
    # converges back to >= 4 and the wait times out).
    wait_for "QUIC mesh settled (connectedPeerCount >= 4)" \
             '[ "$(quic_connected_peer_count)" -ge 4 ] 2>/dev/null' 60
    log_pass "QUIC peer connectivity: $(quic_connected_peer_count) connected peers (cluster size 5; expected ≥ 4, settled)"
}

# Current connectedPeerCount from /api/cluster/topology ("-1" when the endpoint is
# unreachable or the field is absent, so numeric comparisons fail safe).
quic_connected_peer_count() {
    local topology
    topology=$(api_get "/api/cluster/topology")
    local connected
    connected=$(json_value "$topology" "connectedPeerCount")
    echo "${connected:--1}"
}

test_kill_node_and_detect_drop() {
    local leader
    leader=$(cluster_leader)
    local victim
    victim=$(pick_non_leader "$leader" 1)
    : "${victim:=aether-b-node-3}"

    local baseline
    baseline=$(topology_now)

    log_info "Killing node: ${victim}"
    KILLED_VICTIM="$victim"
    kill_node "$victim"

    if ! wait_for_node_departure "$victim" "$baseline" 60; then
        log_fail "No NODE_LEFT/NODE_FAILED event for ${victim} within 60s"
        return 1
    fi
    log_pass "Departure of ${victim} observed on /api/events"

    # 180s base × TIMEOUT_SCALE: 180s docker / 540s cloud. CTM auto-heal on
    # docker-remote takes 60-150s typically (provision + image pull + QUIC handshake +
    # ON_DUTY transition); 90s was too tight without a pre-pulled snapshot.
    if ! wait_for_replacement_of "$victim" "$baseline" 180; then
        log_fail "No NODE_JOINED event for a replacement of ${victim} within 180s"
        return 1
    fi
    log_pass "Replacement for ${victim} observed on /api/events"

    local verdict
    verdict=$(observe_quorum_window "$baseline" 5)
    log_info "Quorum window: ${verdict}"
    case "$verdict" in
        *FAIL*) log_fail "Quorum broken during window: ${verdict}"; return 1 ;;
        *) log_pass "Quorum preserved through kill + replacement" ;;
    esac
}

test_connections_recovered() {
    # Recovery is CTM's job — the previous test already asserted that NODE_JOINED
    # fired for a replacement. Here we assert the post-recovery invariant: the
    # cluster has 5 healthy cores. We deliberately do NOT call
    # `start_node "$KILLED_VICTIM"`: the killed container has left membership,
    # CTM has already provisioned a replacement, and restarting the original
    # would push the cluster to a 6-node "stale + replacement" state that fights
    # the elastic-cluster model.
    if ! wait_for "5 healthy cores after QUIC recovery" \
        "[ \$(cluster_active_core_count) -eq 5 ]" 180; then
        log_fail "Cluster did not converge to 5 healthy cores within 180s after kill+auto-heal"
        return 1
    fi
    assert_cluster_healthy "Cluster healthy after QUIC recovery"
}

run_test "Cluster ready" test_cluster_ready
run_test "All nodes connected" test_all_nodes_connected
run_test "Kill node and detect drop" test_kill_node_and_detect_drop
run_test "Connections recovered" test_connections_recovered
print_summary
