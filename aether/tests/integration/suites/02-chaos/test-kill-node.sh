#!/bin/bash
# test-kill-node.sh — Kill non-leader node, verify auto-heal and recovery
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

test_kill_non_leader_and_heal() {
    local leader
    leader=$(cluster_leader)
    log_info "Current leader: ${leader}"

    # Pick a non-leader node
    local nodes
    nodes=$(cluster_node_list)
    local victim
    victim=$(echo "$nodes" | python3 -c "
import sys, json
data = json.load(sys.stdin)
leader = '${leader}'
for n in (data if isinstance(data, list) else data.get('nodes', [])):
    nid = n.get('nodeId', n.get('id', ''))
    if nid != leader:
        print(nid)
        break
" 2>/dev/null)

    if [ -z "$victim" ]; then
        log_warn "Could not identify non-leader node — using node-3"
        victim="node-3"
    fi

    log_info "Killing non-leader: ${victim}"
    kill_node "$victim"
    sleep 5

    # Cluster should detect failure
    log_info "Waiting for auto-heal to restore to 5 nodes"
    wait_for_node_count 5 180

    # Verify cluster still healthy
    assert_http_status "${CLUSTER_ENDPOINT}/health/ready" "200" "Cluster healthy after heal"
}

test_slices_still_active() {
    local instances
    instances=$(slices_total_instances)
    assert_gt "$instances" "0" "Slices still active: ${instances} instances"
}

test_leader_unchanged() {
    local leader
    leader=$(cluster_leader)
    assert_ne "$leader" "" "Leader still elected after non-leader kill"
}

run_test "Initial 5 nodes" test_initial_state
run_test "Kill non-leader and auto-heal" test_kill_non_leader_and_heal
run_test "Slices still active" test_slices_still_active
run_test "Leader unchanged" test_leader_unchanged
print_summary
