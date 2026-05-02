#!/bin/bash
# test-02-scale-up.sh — Scale 5 -> 7, verify convergence, restore to 5
# Runs after quorum-safety (cluster at baseline 5 nodes)
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "${SCRIPT_DIR}/../../lib/common.sh"
source "${SCRIPT_DIR}/../../lib/cluster.sh"

test_seed_config() {
    wait_for_cluster 60
    wait_for_leader 60
    seed_cluster_config
}

test_baseline_5_nodes() {
    wait_for_node_count_fast 5 60
    local count
    count=$(cluster_node_count)
    assert_eq "$count" "5" "Baseline: 5 nodes"
}

test_scale_up_to_7() {
    log_info "Scaling up to 7 nodes"
    scale_cluster 7
    # Fast-poll variant — `cluster_node_count` (CLI/double-curl) burned ~4-6s/iter
    # and timed out at 300s on Hetzner remote even though the cluster reached 7.
    wait_for_node_count_fast 7 300
    local count
    count=$(cluster_node_count)
    assert_eq "$count" "7" "Scaled to 7 nodes"
}

test_7_nodes_healthy() {
    assert_cluster_healthy "Cluster healthy at 7 nodes"
}

test_restore_to_5() {
    log_info "Restoring to 5 nodes"
    scale_cluster 5
    wait_for_node_count_fast 5 180
    local count
    count=$(cluster_node_count)
    assert_eq "$count" "5" "Restored to 5 nodes"
}

run_test "Seed cluster config" test_seed_config
run_test "Baseline 5 nodes" test_baseline_5_nodes
run_test "Scale up 5 -> 7" test_scale_up_to_7
run_test "7 nodes healthy" test_7_nodes_healthy
run_test "Restore to 5" test_restore_to_5
print_summary
