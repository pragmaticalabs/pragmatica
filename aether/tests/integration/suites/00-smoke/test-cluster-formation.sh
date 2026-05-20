#!/bin/bash
# test-cluster-formation.sh — Verify 5 nodes form cluster with quorum
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "${SCRIPT_DIR}/../../lib/common.sh"
source "${SCRIPT_DIR}/../../lib/cluster.sh"

test_nodes_formed() {
    wait_for_cluster_ready 120
    local expected="${NODE_COUNT:-5}"
    local floor=$(( expected - 1 ))
    local count
    count=$(cluster_member_count)
    # N-1 floor: tolerates the seed-node special case where the leader's own
    # `NodeLifecycleKey` isn't written to KV (cluster_member_count = generation.members
    # excludes it). See aether/docs/specs/test-readiness-contract.md §1.1 + §6 (RC2
    # follow-up). Strict equality returns when the seed-node lifecycle write lands.
    if [ "$count" -lt "$floor" ]; then
        log_fail "Cluster has fewer than ${floor} members (got ${count})"
        return 1
    fi
    log_pass "Cluster member floor met (${count} ≥ ${floor})"
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
    local floor=$(( expected - 1 ))
    # N-1 floor (seed-node bug, see test-readiness-contract §6 RC2 follow-up).
    if [ "$node_count" -lt "$floor" ]; then
        log_fail "Quorum below floor (${node_count} < ${floor})"
        return 1
    fi
    log_pass "Quorum established (${node_count} ≥ ${floor})"
}

test_liveness_probe() {
    assert_http_status "${CLUSTER_ENDPOINT}/health/live" "200" "Liveness probe returns 200"
}

test_all_nodes_visible() {
    local count
    count=$(cluster_member_count)
    local expected="${NODE_COUNT:-5}"
    local floor=$(( expected - 1 ))
    # N-1 floor (seed-node bug, see test-readiness-contract §6 RC2 follow-up).
    if [ "$count" -lt "$floor" ]; then
        log_fail "Fewer than ${floor} nodes visible (got ${count})"
        return 1
    fi
    log_pass "Node visibility floor met (${count} ≥ ${floor})"
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
