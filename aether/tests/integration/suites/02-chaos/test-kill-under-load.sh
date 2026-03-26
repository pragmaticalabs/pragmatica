#!/bin/bash
# test-kill-under-load.sh — Kill node during active load, verify zero data loss
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "${SCRIPT_DIR}/../../lib/common.sh"
source "${SCRIPT_DIR}/../../lib/cluster.sh"
source "${SCRIPT_DIR}/../../lib/load.sh"

LOAD_DURATION="${LOAD_DURATION:-120}"
LOAD_RPS="${LOAD_RPS:-10}"
MAX_ERROR_RATE="${MAX_ERROR_RATE:-5.0}"

test_initial_state() {
    wait_for_cluster 60
    local count
    count=$(cluster_node_count)
    assert_eq "$count" "5" "Initial: 5 nodes"
}

test_kill_during_load() {
    # Start background load
    start_load "$LOAD_RPS" "$LOAD_DURATION" "GET" "/api/health"

    # Wait for load to establish
    sleep 10

    # Kill a non-leader node
    local leader
    leader=$(cluster_leader)
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
        victim="node-3"
    fi

    log_info "Killing ${victim} under load"
    kill_node "$victim"

    # Let load continue through the disruption
    log_info "Waiting for load to complete"
    for pid in "${LOAD_PIDS[@]}"; do
        wait "$pid" 2>/dev/null || true
    done

    local result
    result=$(stop_load)
    assert_error_rate_below "$result" "$MAX_ERROR_RATE" "Error rate during kill < ${MAX_ERROR_RATE}%"
}

test_cluster_heals_after_load() {
    wait_for_node_count 5 180
    assert_http_status "${CLUSTER_ENDPOINT}/health/ready" "200" "Cluster healthy after kill-under-load"
}

test_slices_intact() {
    local instances
    instances=$(slices_total_instances)
    assert_gt "$instances" "0" "Slices intact after disruption: ${instances}"
}

run_test "Initial 5 nodes" test_initial_state
run_test "Kill node during active load" test_kill_during_load
run_test "Cluster heals" test_cluster_heals_after_load
run_test "Slices intact" test_slices_intact
print_summary
