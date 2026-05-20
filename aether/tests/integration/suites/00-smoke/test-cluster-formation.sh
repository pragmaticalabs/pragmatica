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
    # Strict equality: the seed-node lifecycle write bug is fixed (every node — including
    # the initial leader — appears in the generation snapshot). See
    # aether/docs/specs/test-readiness-contract.md §6.
    if [ "$count" -ne "$expected" ]; then
        log_fail "Cluster has ${count} members, expected ${expected}"
        return 1
    fi
    log_pass "Cluster has ${count} members"
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
    # Strict equality (seed-node bug fixed; see test-readiness-contract §6).
    if [ "$node_count" -ne "$expected" ]; then
        log_fail "Quorum mismatch (${node_count} != ${expected})"
        return 1
    fi
    log_pass "Quorum established (${node_count})"
}

test_liveness_probe() {
    assert_http_status "${CLUSTER_ENDPOINT}/health/live" "200" "Liveness probe returns 200"
}

test_all_nodes_visible() {
    local count
    count=$(cluster_member_count)
    local expected="${NODE_COUNT:-5}"
    # Strict equality (seed-node bug fixed; see test-readiness-contract §6).
    if [ "$count" -ne "$expected" ]; then
        log_fail "Expected ${expected} nodes visible, got ${count}"
        return 1
    fi
    log_pass "All ${count} nodes visible"
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
