#!/bin/bash
# test-cluster-formation.sh — Verify 5 nodes form cluster with quorum
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "${SCRIPT_DIR}/../../lib/common.sh"
source "${SCRIPT_DIR}/../../lib/cluster.sh"

test_nodes_formed() {
    wait_for_cluster_ready 120
    local expected="${NODE_COUNT:-5}"
    local count
    count=$(cluster_member_count)
    # Equality, not ≥ — `coreCount > NODE_COUNT` indicates phantom KV state
    # (e.g., persisted aether_pgdata from a previous run replaying ghost ON_DUTY
    # peers). Vacuous "≥ 5" hides cluster contamination.
    assert_eq "$count" "$expected" "Cluster has exactly ${expected} nodes (got ${count})"
}

test_leader_elected() {
    local leader
    leader=$(cluster_leader)
    # Reject empty AND the literal "none" — the management API returns "none"
    # when no leader is elected, which `assert_ne "" ""` previously accepted.
    if [ -z "$leader" ] || [ "$leader" = "none" ] || [ "$leader" = "null" ]; then
        log_fail "Leader elected: got '${leader:-<empty>}' — expected a real node id"
        return 1
    fi
    log_pass "Leader elected: ${leader}"
}

test_quorum_established() {
    local node_count
    node_count=$(cluster_member_count)
    local expected="${NODE_COUNT:-5}"
    # Tight: quorum is established only when count == expected, not just ≥ 3.
    assert_eq "$node_count" "$expected" "Quorum established (${node_count} nodes == ${expected})"
}

test_liveness_probe() {
    assert_http_status "${CLUSTER_ENDPOINT}/health/live" "200" "Liveness probe returns 200"
}

test_all_nodes_visible() {
    local count
    count=$(cluster_member_count)
    local expected="${NODE_COUNT:-5}"
    assert_eq "$count" "$expected" "All nodes visible (${count} == ${expected})"
}

test_status_endpoint() {
    local status
    status=$(cluster_status)
    assert_ne "$status" "" "Status endpoint returns data"
    local node_id
    node_id=$(aether_field status nodeId)
    assert_ne "$node_id" "" "Node ID present in status"
}

test_events_available() {
    local events
    events=$(cluster_events)
    assert_ne "$events" "" "Events endpoint returns data"
}

run_test "Cluster has 5 nodes" test_nodes_formed
run_test "Leader elected" test_leader_elected
run_test "Quorum established (readiness)" test_quorum_established
run_test "Liveness probe" test_liveness_probe
run_test "All nodes visible" test_all_nodes_visible
run_test "Status endpoint" test_status_endpoint
run_test "Events available" test_events_available
print_summary
