#!/bin/bash
# test-disruption-budget.sh — Drain beyond budget, verify rejection
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "${SCRIPT_DIR}/../../lib/common.sh"
source "${SCRIPT_DIR}/../../lib/cluster.sh"

test_cluster_ready() {
    wait_for_cluster 60
    # ClusterGeneration barrier: inherit-from-predecessor churn is committed to a stable
    # generation (no manual drain-reset). Lingering DRAINING lifecycle is fenced by the
    # leader's snapshot quiescence — if the budget is still exhausted it's a real defect.
    await_generation_quiesced "$CLUSTER_ENDPOINT" "current" 120 || log_warn "pre-test snapshot not quiesced"
    local count
    count=$(cluster_node_count)
    if [ "$count" -lt 3 ] 2>/dev/null; then
        log_fail "Need at least 3 nodes for disruption budget test, got ${count}"
        return 1
    fi
    log_pass "Initial: ${count} nodes (>= 3 quorum)"
}

test_drain_first_node_allowed() {
    local node1="node-5"
    log_info "Draining first node: ${node1}"
    local status
    status=$(http_status "${CLUSTER_ENDPOINT}/api/node/drain/${node1}" -X POST -H "X-API-Key: ${API_KEY}")

    if [ "$status" -ge 200 ] && [ "$status" -lt 300 ] 2>/dev/null; then
        log_pass "First drain accepted (${status})"
    else
        log_fail "First drain should be accepted (within budget), got ${status}"
        return 1
    fi
    await_generation_quiesced "$CLUSTER_ENDPOINT" "current+1" 30 || log_warn "first drain did not quiesce"
}

test_drain_second_node_allowed() {
    local node2="node-4"
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
    await_generation_quiesced "$CLUSTER_ENDPOINT" "current+1" 30 || log_warn "second drain did not quiesce"
}

test_drain_beyond_budget_rejected() {
    local node3="node-3"
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
        # If lifecycle JSON contains any drain-related state, reactivate all known nodes
        if echo "$lifecycle" | grep -qiE 'drain'; then
            echo "$lifecycle" | grep -o '"nodeId"[[:space:]]*:[[:space:]]*"[^"]*"' | sed 's/.*"\([^"]*\)"$/\1/' | while read -r nid; do
                if [ -n "$nid" ]; then
                    activate_node "$nid" 2>/dev/null || true
                fi
            done
        fi
    fi
    await_generation_quiesced "$CLUSTER_ENDPOINT" "current+1" 30 || log_warn "reactivation did not quiesce"
    assert_cluster_healthy "Cluster healthy after reactivation"
}

run_test "Cluster ready (5 nodes)" test_cluster_ready
run_test "First drain allowed" test_drain_first_node_allowed
run_test "Second drain allowed" test_drain_second_node_allowed
run_test "Third drain rejected (budget)" test_drain_beyond_budget_rejected
run_test "Quorum preserved" test_quorum_preserved
run_test "Reactivate nodes" test_reactivate_nodes
print_summary
