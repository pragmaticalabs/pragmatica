#!/bin/bash
# test-disruption-budget.sh — Drain beyond budget, verify rejection
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "${SCRIPT_DIR}/../../lib/common.sh"
source "${SCRIPT_DIR}/../../lib/cluster.sh"

test_cluster_ready() {
    wait_for_cluster 60
    wait_for_node_count 5 30 || log_warn "Only $(cluster_node_count) nodes available — proceeding"
    local count
    count=$(cluster_node_count)
    if [ "$count" -lt 3 ] 2>/dev/null; then
        log_fail "Need at least 3 nodes for disruption budget test, got ${count}"
        return 1
    fi
    log_pass "Initial: ${count} nodes (>= 3 quorum)"
}

test_drain_first_node_allowed() {
    local nodes
    nodes=$(cluster_node_list)
    local node1
    node1=$(echo "$nodes" | python3 -c "
import sys, json
data = json.load(sys.stdin)
entries = data if isinstance(data, list) else data.get('nodes', [])
if entries:
    print(entries[-1].get('nodeId', entries[-1].get('id', '')))
" 2>/dev/null)

    if [ -z "$node1" ]; then
        log_warn "Could not identify node — using node-5"
        node1="node-5"
    fi

    log_info "Draining first node: ${node1}"
    local status
    status=$(http_status "${CLUSTER_ENDPOINT}/api/node/drain/${node1}" -X POST -H "X-API-Key: ${API_KEY}")

    if [ "$status" -ge 200 ] && [ "$status" -lt 300 ] 2>/dev/null; then
        log_pass "First drain accepted (${status})"
    else
        log_fail "First drain should be accepted (within budget), got ${status}"
        return 1
    fi
    sleep 3
}

test_drain_second_node_allowed() {
    local nodes
    nodes=$(cluster_node_list)
    local node2
    node2=$(echo "$nodes" | python3 -c "
import sys, json
data = json.load(sys.stdin)
entries = data if isinstance(data, list) else data.get('nodes', [])
if len(entries) >= 2:
    print(entries[-2].get('nodeId', entries[-2].get('id', '')))
" 2>/dev/null)

    if [ -z "$node2" ]; then
        log_warn "Could not identify second node — using node-4"
        node2="node-4"
    fi

    log_info "Draining second node: ${node2}"
    local status
    status=$(http_status "${CLUSTER_ENDPOINT}/api/node/drain/${node2}" -X POST -H "X-API-Key: ${API_KEY}")
    log_info "Second drain response: ${status}"
    if [ "$status" -ge 200 ] && [ "$status" -lt 300 ] 2>/dev/null; then
        log_pass "Second drain accepted (${status})"
    else
        log_fail "Second drain should be accepted (within budget), got ${status}"
        return 1
    fi
    sleep 3
}

test_drain_beyond_budget_rejected() {
    local nodes
    nodes=$(cluster_node_list)
    local node3
    node3=$(echo "$nodes" | python3 -c "
import sys, json
data = json.load(sys.stdin)
entries = data if isinstance(data, list) else data.get('nodes', [])
if len(entries) >= 3:
    print(entries[-3].get('nodeId', entries[-3].get('id', '')))
" 2>/dev/null)

    if [ -z "$node3" ]; then
        log_warn "Could not identify third node — using node-3"
        node3="node-3"
    fi

    log_info "Attempting to drain third node (should be rejected by budget): ${node3}"
    local status
    status=$(http_status "${CLUSTER_ENDPOINT}/api/node/drain/${node3}" -X POST -H "X-API-Key: ${API_KEY}")

    if [ "$status" -eq 409 ] 2>/dev/null; then
        log_pass "Third drain rejected by disruption budget (${status} Conflict)"
    elif [ "$status" -ge 400 ] && [ "$status" -lt 500 ] 2>/dev/null; then
        log_pass "Third drain rejected by disruption budget (${status})"
    elif [ "$status" -eq 503 ] 2>/dev/null; then
        log_pass "Third drain rejected — service unavailable (${status})"
    else
        log_fail "Third drain should be rejected by disruption budget, got ${status}"
        return 1
    fi
}

test_quorum_preserved() {
    assert_cluster_healthy "Quorum preserved despite drains"
}

test_reactivate_nodes() {
    # Re-activate any drained nodes
    local lifecycle
    lifecycle=$(get_node_lifecycle)
    if [ -n "$lifecycle" ]; then
        log_info "Reactivating drained nodes"
        echo "$lifecycle" | python3 -c "
import sys, json, re
try:
    data = json.load(sys.stdin)
    nodes = data if isinstance(data, list) else data.get('nodes', [])
    for n in nodes:
        state = str(n.get('state', n.get('lifecycle', '')))
        if re.search(r'drain(ing|ed)?', state, re.IGNORECASE):
            print(n.get('nodeId', n.get('id', '')))
except:
    pass
" 2>/dev/null | while read -r nid; do
            if [ -n "$nid" ]; then
                activate_node "$nid" 2>/dev/null || true
            fi
        done
    fi
    sleep 5
    assert_cluster_healthy "Cluster healthy after reactivation"
}

run_test "Cluster ready (5 nodes)" test_cluster_ready
run_test "First drain allowed" test_drain_first_node_allowed
run_test "Second drain allowed" test_drain_second_node_allowed
run_test "Third drain rejected (budget)" test_drain_beyond_budget_rejected
run_test "Quorum preserved" test_quorum_preserved
run_test "Reactivate nodes" test_reactivate_nodes
print_summary
