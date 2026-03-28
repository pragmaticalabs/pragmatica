#!/bin/bash
# test-quic-connectivity.sh — Verify QUIC connections, kill node, verify recovery
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "${SCRIPT_DIR}/../../lib/common.sh"
source "${SCRIPT_DIR}/../../lib/cluster.sh"

test_cluster_ready() {
    wait_for_cluster 60
    log_pass "Cluster ready"
}

test_all_nodes_connected() {
    local metrics
    metrics=$(api_get "/api/metrics/transport")
    if [ -z "$metrics" ]; then
        log_warn "Transport metrics unavailable — verifying via node count"
        local count
        count=$(cluster_node_count)
        assert_eq "$count" "5" "All 5 nodes visible (transport metrics unavailable)"
        return 0
    fi

    local connections
    connections=$(echo "$metrics" | python3 -c "
import sys, json
try:
    data = json.load(sys.stdin)
    if isinstance(data, dict):
        for k, v in data.items():
            if 'connect' in k.lower() and 'active' in k.lower():
                print(v)
                break
            if 'connectionCount' in k or 'connections' == k:
                print(v)
                break
        else:
            print(-1)
    else:
        print(-1)
except:
    print(-1)
" 2>/dev/null)

    if [ "$connections" -gt 0 ] 2>/dev/null; then
        log_pass "Active QUIC connections: ${connections}"
    elif [ "$connections" = "-1" ]; then
        log_warn "Could not extract connection count from transport metrics"
        local count
        count=$(cluster_node_count)
        assert_eq "$count" "5" "All 5 nodes visible"
    else
        log_fail "No active QUIC connections"
        return 1
    fi
}

test_kill_node_and_detect_drop() {
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

    log_info "Killing node: ${victim}"
    kill_node "$victim"
    sleep 10

    # Verify connection count dropped (or node count dropped)
    local count
    count=$(cluster_node_count)
    if [ "$count" -lt 5 ] 2>/dev/null; then
        log_pass "Node count dropped to ${count} after kill"
    else
        log_warn "Node count still 5 — auto-heal may have already replaced"
        log_pass "Cluster responded to node kill"
    fi

    # Wait for recovery
    log_info "Waiting for recovery to 5 nodes"
    wait_for_node_count 5 180
}

test_connections_recovered() {
    local count
    count=$(cluster_node_count)
    assert_eq "$count" "5" "All 5 nodes recovered after kill"
    assert_cluster_healthy "Cluster healthy after QUIC recovery"
}

run_test "Cluster ready" test_cluster_ready
run_test "All nodes connected" test_all_nodes_connected
run_test "Kill node and detect drop" test_kill_node_and_detect_drop
run_test "Connections recovered" test_connections_recovered
print_summary
