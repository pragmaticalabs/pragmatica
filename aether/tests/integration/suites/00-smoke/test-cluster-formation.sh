#!/bin/bash
# test-cluster-formation.sh — Verify 5 nodes form cluster with quorum
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "${SCRIPT_DIR}/../../lib/common.sh"
source "${SCRIPT_DIR}/../../lib/cluster.sh"

test_nodes_formed() {
    wait_for_cluster 120
    local count
    count=$(cluster_node_count)
    assert_eq "$count" "5" "Cluster has 5 nodes"
}

test_leader_elected() {
    local leader
    leader=$(cluster_leader)
    assert_ne "$leader" "" "Leader elected: ${leader}"
}

test_quorum_established() {
    local node_count
    node_count=$(cluster_node_count)
    assert_ge "$node_count" "3" "Quorum established (${node_count} nodes >= 3)"
}

test_liveness_probe() {
    assert_http_status "${CLUSTER_ENDPOINT}/health/live" "200" "Liveness probe returns 200"
}

test_all_nodes_visible() {
    local count
    count=$(cluster_node_count)
    assert_eq "$count" "5" "All 5 nodes visible in cluster status"
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
