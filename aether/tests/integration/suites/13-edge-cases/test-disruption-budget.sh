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
    # node-5 is the docker-style fixture id; on cloud it maps to ${CLOUD_SOURCE_NAME}-core-4.
    # The runtime stores NodeLifecycleKey under the actual node id, so the drain endpoint
    # path parameter must use the translated form or it returns 500 (no such lifecycle).
    local node1
    node1=$(to_node_id "node-5")
    log_info "Draining first node: ${node1}"
    local status
    status=$(http_status "${CLUSTER_ENDPOINT}/api/node/drain/${node1}" -X POST -H "X-API-Key: ${API_KEY}")

    if [ "$status" -ge 200 ] && [ "$status" -lt 300 ] 2>/dev/null; then
        log_pass "First drain accepted (${status})"
    else
        log_fail "First drain should be accepted (within budget), got ${status}"
        return 1
    fi
    # Why no await_generation_quiesced here: CTM auto-heal would provision a replacement
    # during the wait, restoring ON_DUTY count to 5 and defeating the budget test. The
    # budget is enforced against live ON_DUTY count, so we must drain faster than
    # CTM's replacement cycle to keep multiple nodes simultaneously unavailable.
}

test_drain_second_node_allowed() {
    local node2
    node2=$(to_node_id "node-4")
    log_info "Draining second node: ${node2}"
    local status
    status=$(http_status "${CLUSTER_ENDPOINT}/api/node/drain/${node2}" -X POST -H "X-API-Key: ${API_KEY}")
    log_info "Second drain response: ${status}"
    # Race-tolerant: 200 = within budget; 409 = budget rejected because CTM auto-heal
    # transitioned node-5 through DECOMMISSIONED and the replacement is still JOINING,
    # so live ON_DUTY=3 already and a second drain would drop operational below quorum.
    # Both outcomes prove the budget is enforced against live capacity.
    if [ "$status" -ge 200 ] && [ "$status" -lt 300 ] 2>/dev/null; then
        log_pass "Second drain accepted (${status}) — within budget"
    elif [ "$status" -eq 409 ] 2>/dev/null; then
        log_pass "Second drain rejected (${status}) — auto-heal raced; budget guarded quorum"
    else
        log_fail "Second drain unexpected status ${status}"
        return 1
    fi
}

test_drain_beyond_budget_rejected() {
    local node3
    node3=$(to_node_id "node-3")
    log_info "Attempting to drain third node: ${node3}"
    local status
    status=$(http_status "${CLUSTER_ENDPOINT}/api/node/drain/${node3}" -X POST -H "X-API-Key: ${API_KEY}")
    log_info "Third drain response: ${status}"

    # TODO: this test cannot prove disruption-budget enforcement on a fixture with
    # CTM auto-heal active. With auto-heal, earlier-drained nodes are replaced
    # before the third drain lands, ON_DUTY is restored, and the budget is *not*
    # threatened — so 200 is "correct" here. With auto-heal disabled, this drain
    # would deterministically return 409 (budget tripped). Accepting both outcomes
    # (current state) means the test passes for *every* possible response, which
    # makes it incapable of catching a budget regression.
    #
    # Required capability: a fixture toggle to disable CTM auto-heal for this
    # suite (e.g., FIXTURE=no-auto-heal in 13-edge-cases/suite.conf), then assert
    # exact status=409 with a problem+json body whose `type` identifies the
    # disruption-budget rejection. Until that toggle exists, the test is broken
    # by design and must FAIL so the gap stays visible.
    log_fail "TODO: test cannot prove budget rejection without disabling CTM auto-heal in fixture (status was ${status}). Required: fixture toggle + assert_eq status 409 + problem+json type check."
    return 1
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
            local errfile
            errfile=$(mktemp)
            echo "$lifecycle" | grep -o '"nodeId"[[:space:]]*:[[:space:]]*"[^"]*"' | sed 's/.*"\([^"]*\)"$/\1/' | while read -r nid; do
                if [ -n "$nid" ]; then
                    # Capture stderr; surface failures as warnings instead of `|| true`
                    # which silently masks management-API regressions during cleanup.
                    if ! activate_node "$nid" 2>"$errfile"; then
                        log_warn "activate_node ${nid} failed: $(head -c 300 < "$errfile")"
                    fi
                fi
            done
            rm -f "$errfile"
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
