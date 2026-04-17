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
    log_pass "Cluster ready"
}

test_all_nodes_connected() {
    local metrics
    metrics=$(api_get "/api/metrics/transport")
    if [ -z "$metrics" ]; then
        log_warn "Transport metrics unavailable — verifying via node count"
        local count
        count=$(cluster_node_count)
        assert_eq "$count" "5" "All 5 nodes visible (transport metrics unavailable)"
        return 0
    fi

    local connections
    connections=$(json_value "$metrics" "connectionCount")
    if [ -z "$connections" ]; then
        connections=$(json_value "$metrics" "connections")
    fi
    if [ -z "$connections" ]; then
        connections=$(echo "$metrics" | grep -oi '"[^"]*connect[^"]*active[^"]*"[[:space:]]*:[[:space:]]*[0-9]*' | head -1 | grep -o '[0-9]*$')
    fi
    connections="${connections:--1}"

    if [ "$connections" -gt 0 ] 2>/dev/null; then
        log_pass "Active QUIC connections: ${connections}"
    elif [ "$connections" = "-1" ]; then
        log_warn "Could not extract connection count from transport metrics"
        local count
        count=$(cluster_node_count)
        assert_eq "$count" "5" "All 5 nodes visible"
    else
        log_fail "No active QUIC connections"
        return 1
    fi
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
    # Drop any CTM-provisioned replacement so the set of live nodes matches the
    # fixed compose-node set. Then restart the compose container we killed.
    drop_ctm_replacements
    if [ -n "${KILLED_VICTIM:-}" ]; then
        start_node "$KILLED_VICTIM"
    fi
    wait_for_node_count 5 180
    assert_cluster_healthy "Cluster healthy after QUIC recovery"
}

run_test "Cluster ready" test_cluster_ready
run_test "All nodes connected" test_all_nodes_connected
run_test "Kill node and detect drop" test_kill_node_and_detect_drop
run_test "Connections recovered" test_connections_recovered
print_summary
