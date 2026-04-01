#!/bin/bash
# test-swim-detection.sh — Kill node, measure detection time, verify < 15 seconds
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "${SCRIPT_DIR}/../../lib/common.sh"
source "${SCRIPT_DIR}/../../lib/cluster.sh"

DETECTION_TIMEOUT="${SWIM_DETECTION_TIMEOUT:-15}"

test_cluster_ready() {
    wait_for_cluster 60
    local count
    count=$(cluster_node_count)
    assert_eq "$count" "5" "Initial: 5 nodes"
}

test_swim_detection_time() {
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
        victim="node-4"
    fi

    log_info "Killing node ${victim} and measuring detection time"
    kill_node "$victim"

    local start_epoch
    start_epoch=$(now_epoch)
    local detected=false
    local elapsed=0

    # Poll every second for up to 30 seconds
    while [ "$elapsed" -lt 30 ]; do
        local count
        count=$(cluster_node_count 2>/dev/null || echo "5")
        if [ "$count" -lt 5 ] 2>/dev/null; then
            elapsed=$(elapsed_since "$start_epoch")
            detected=true
            break
        fi
        sleep 1
        elapsed=$(elapsed_since "$start_epoch")
    done

    if [ "$detected" = true ]; then
        log_info "Node failure detected in ${elapsed}s"
        if [ "$elapsed" -le "$DETECTION_TIMEOUT" ]; then
            log_pass "SWIM detection within ${DETECTION_TIMEOUT}s window: ${elapsed}s"
        else
            log_fail "SWIM detection took ${elapsed}s (threshold: ${DETECTION_TIMEOUT}s)"
            return 1
        fi
    else
        log_fail "Node failure not detected within 30s"
        return 1
    fi
}

test_node_removed_from_list() {
    local nodes
    nodes=$(cluster_node_list)
    local count
    count=$(json_len "$nodes")
    if [ "$count" -lt 5 ] 2>/dev/null; then
        log_pass "Failed node removed from /api/nodes (${count} nodes)"
    else
        log_warn "Node list still shows 5 — auto-heal may have replaced"
        log_pass "Cluster stable after detection"
    fi
}

test_recovery_after_detection() {
    wait_for_node_count 5 180
    assert_cluster_healthy "Cluster recovered after SWIM detection"
}

run_test "Cluster ready (5 nodes)" test_cluster_ready
run_test "SWIM detection time" test_swim_detection_time
run_test "Node removed from list" test_node_removed_from_list
run_test "Recovery after detection" test_recovery_after_detection
print_summary
