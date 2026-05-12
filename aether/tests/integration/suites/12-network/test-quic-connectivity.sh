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
    wait_for_cluster 60
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
    # self). For a healthy 5-node cluster each node sees 4 peers; the assertion
    # `>= 4` tolerates transient single-peer glitches without masking systemic
    # connectivity loss.
    local topology
    topology=$(api_get "/api/cluster/topology")
    if [ -z "$topology" ]; then
        log_fail "GET /api/cluster/topology returned empty — cannot verify QUIC connectivity"
        return 1
    fi
    local connected
    connected=$(json_value "$topology" "connectedPeerCount")
    connected="${connected:--1}"
    if [ "$connected" -ge 4 ] 2>/dev/null; then
        log_pass "QUIC peer connectivity: ${connected} connected peers (cluster size 5; expected ≥ 4)"
        return 0
    fi
    log_fail "QUIC peer connectivity insufficient: connectedPeerCount=${connected} (expected ≥ 4 for 5-node cluster)"
    return 1
}

test_kill_node_and_detect_drop() {
    local leader
    leader=$(cluster_leader)
    local victim
    victim=$(pick_non_leader "$leader" 1)
    : "${victim:=node-3}"

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
    # cluster has 5 ON_DUTY healthy cores. We deliberately do NOT call
    # `start_node "$KILLED_VICTIM"`: the killed container is DECOMMISSIONED
    # (single-writer rule on NodeLifecycleKey), CTM has already provisioned a
    # replacement, and restarting the original would push the cluster to a
    # 6-node "stale + replacement" state that fights the elastic-cluster model.
    if ! wait_for "5 ON_DUTY healthy cores after QUIC recovery" \
        "[ \$(cluster_node_count_on_duty_healthy) -eq 5 ]" 90; then
        log_fail "Cluster did not converge to 5 ON_DUTY healthy cores within 90s after kill+auto-heal"
        return 1
    fi
    assert_cluster_healthy "Cluster healthy after QUIC recovery"
}

run_test "Cluster ready" test_cluster_ready
run_test "All nodes connected" test_all_nodes_connected
run_test "Kill node and detect drop" test_kill_node_and_detect_drop
run_test "Connections recovered" test_connections_recovered
print_summary
