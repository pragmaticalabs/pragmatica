#!/bin/bash
# test-kill-multiple.sh — Kill 2 nodes sequentially, verify recovery
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "${SCRIPT_DIR}/../../lib/common.sh"
source "${SCRIPT_DIR}/../../lib/cluster.sh"

test_initial_state() {
    wait_for_cluster 60
    local count
    count=$(cluster_node_count)
    assert_eq "$count" "5" "Initial: 5 nodes"
}

test_kill_two_nodes_sequentially() {
    local leader
    leader=$(cluster_leader)

    # Get two non-leader nodes
    local nodes
    nodes=$(cluster_node_list)
    local victims
    victims=$(echo "$nodes" | python3 -c "
import sys, json
data = json.load(sys.stdin)
leader = '${leader}'
found = []
for n in (data if isinstance(data, list) else data.get('nodes', [])):
    nid = n.get('nodeId', n.get('id', ''))
    if nid != leader and len(found) < 2:
        found.append(nid)
print(' '.join(found))
" 2>/dev/null)

    local victim1 victim2
    victim1=$(echo "$victims" | awk '{print $1}')
    victim2=$(echo "$victims" | awk '{print $2}')

    if [ -z "$victim1" ] || [ -z "$victim2" ]; then
        log_warn "Could not identify 2 non-leader nodes — using node-3, node-4"
        victim1="node-3"
        victim2="node-4"
    fi

    # Kill first node
    log_info "Killing node 1: ${victim1}"
    kill_node "$victim1"
    sleep 10

    # Kill second node
    log_info "Killing node 2: ${victim2}"
    kill_node "$victim2"
    sleep 10

    # Cluster should still have quorum (3 of 5)
    assert_http_status "${CLUSTER_ENDPOINT}/health/ready" "200" "Cluster has quorum with 3 nodes"
}

test_auto_heal_restores() {
    log_info "Waiting for auto-heal to restore both nodes"
    wait_for_node_count 5 240

    local count
    count=$(cluster_node_count)
    assert_eq "$count" "5" "Cluster restored to 5 nodes"
}

test_slices_active_after_multi_kill() {
    wait_for_slices_active 1 60
    local instances
    instances=$(slices_total_instances)
    assert_gt "$instances" "0" "Slices active after multi-kill: ${instances}"
}

test_health_after_recovery() {
    assert_http_status "${CLUSTER_ENDPOINT}/health/ready" "200" "Cluster healthy after multi-node recovery"
}

run_test "Initial 5 nodes" test_initial_state
run_test "Kill 2 nodes sequentially" test_kill_two_nodes_sequentially
run_test "Auto-heal restores both" test_auto_heal_restores
run_test "Slices active after multi-kill" test_slices_active_after_multi_kill
run_test "Health after recovery" test_health_after_recovery
print_summary
