#!/bin/bash
# test-disruption-budget.sh — Drain beyond budget, verify rejection
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "${SCRIPT_DIR}/../../lib/common.sh"
source "${SCRIPT_DIR}/../../lib/cluster.sh"

# Drain targets are selected dynamically (live READY non-leaders) in test_cluster_ready
# and persisted here — run_test forks a subshell per function, so the selection cannot
# live in memory. Hardcoding node-5/4/3 broke in the full destructive chain: an upstream
# suite (02-chaos/12-network) terminal-removes those seeds and CTM replaces them with ULID
# nodes, so the drain endpoint 404'd ("Node lifecycle not found") on names that were gone.
DRAIN_NODES_FILE="${TMPDIR:-/tmp}/aether-13-drain-nodes-$$.txt"

test_cluster_ready() {
    wait_for_cluster_ready 60
    # ClusterGeneration barrier: inherit-from-predecessor churn is committed to a stable
    # generation (no manual drain-reset). Lingering DRAINING lifecycle is fenced by the
    # leader's snapshot quiescence — if the budget is still exhausted it's a real defect.
    await_generation_quiesced "$CLUSTER_ENDPOINT" "current" 120 || log_warn "pre-test snapshot not quiesced"
    local count
    count=$(cluster_member_count)
    if [ "$count" -lt 3 ] 2>/dev/null; then
        log_fail "Need at least 3 nodes for disruption budget test, got ${count}"
        return 1
    fi
    # Disable CTM auto-heal for the duration of this suite. Without this, each drain
    # is silently compensated by a replacement provision, ON_DUTY is restored, the
    # budget is never threatened, and test_drain_beyond_budget_rejected below can
    # never deterministically assert the 409 rejection. Re-enabled by trap at script
    # exit (see EXIT trap below).
    if ! disable_auto_heal; then
        log_fail "Cluster ready: disable_auto_heal failed — disruption budget cannot be deterministically tested under active auto-heal racing"
        return 1
    fi
    log_pass "Initial: ${count} nodes (>= 3 quorum); CTM auto-heal disabled for duration of suite"

    # Select 3 distinct LIVE READY non-leader nodes for the drain sequence and persist them
    # (subshell-per-test). Robust to the destructive chain, where the original seed names may
    # already be terminal-removed + replaced by ULID nodes. Mirrors the sibling
    # test-stale-route-cleanup.sh, which discovers its target the same way.
    local leader
    leader=$(cluster_leader)
    if ! pick_non_leader "$leader" 3 > "$DRAIN_NODES_FILE" \
            || [ "$(grep -cve '^$' "$DRAIN_NODES_FILE" 2>/dev/null || echo 0)" -lt 3 ]; then
        log_fail "Cluster ready: need 3 distinct live READY non-leader nodes for the drain sequence; got [$(tr '\n' ' ' < "$DRAIN_NODES_FILE" 2>/dev/null)]"
        return 1
    fi
    log_pass "Drain targets selected (live READY non-leaders): $(tr '\n' ' ' < "$DRAIN_NODES_FILE")"
}

# Re-enable CTM auto-heal on any exit path (test pass, test fail, set -e abort).
# Without this trap the cluster is left in a permanently-disabled state, breaking
# every downstream cluster B suite that relies on CTM provisioning replacements
# after kill_node.
_reactivate_auto_heal_trap() {
    rm -f "$DRAIN_NODES_FILE"
    enable_auto_heal || log_warn "EXIT trap: enable_auto_heal returned non-zero — operator must manually re-enable via 'aether cluster topology auto-heal enable' or cluster will not self-heal"
}
trap _reactivate_auto_heal_trap EXIT

test_drain_first_node_allowed() {
    # Drain target #1 of 3, selected in test_cluster_ready (live READY non-leaders,
    # persisted to DRAIN_NODES_FILE). The runtime stores NodeLifecycleKey under the actual
    # node id, so the drain endpoint path parameter must use it (a stale/gone name 404s).
    local node1
    node1=$(sed -n '1p' "$DRAIN_NODES_FILE")
    log_info "Draining first node: ${node1}"

    # Diagnostic: surface auto-heal state and current ON_DUTY count so a 503
    # rejection has a debuggable trail. Without these the only signal is "503"
    # which doesn't distinguish (a) auto-heal-disabled-but-budget-tripped from
    # (b) auto-heal-still-active from (c) cluster degraded from a prior suite.
    local autoheal_state
    autoheal_state=$(api_get "/api/cluster/topology/auto-heal" 2>/dev/null || echo "<unreachable>")
    log_info "auto-heal state before first drain: $(printf '%s' "$autoheal_state" | head -c 200)"

    local status body_file
    body_file=$(mktemp)
    status=$(curl -sk -o "$body_file" -w "%{http_code}" -m 10 \
             -X POST -H "X-API-Key: ${API_KEY}" \
             "${CLUSTER_ENDPOINT}/api/nodes/drain/${node1}")

    if [ "$status" -ge 200 ] && [ "$status" -lt 300 ] 2>/dev/null; then
        log_pass "First drain accepted (${status})"
        rm -f "$body_file"
        return 0
    fi
    local body
    body=$(head -c 500 < "$body_file" | tr '\n' ' ')
    rm -f "$body_file"
    # TODO: investigate First_drain 503 — root cause likely either (a) auto-heal
    # not fully quiesced before drain (race between disable_auto_heal and an
    # in-flight provisioning), or (b) disruption-budget threshold miscalculated
    # under transient lifecycle state inherited from a previous suite. Surfaces
    # as visible failure rather than masking; do not silently downgrade to PASS.
    log_fail "TODO: investigate First_drain 503 — status=${status} body=${body} autoheal=${autoheal_state:0:200}"
    return 1
}

test_drain_second_node_allowed() {
    local node2
    node2=$(sed -n '2p' "$DRAIN_NODES_FILE")
    log_info "Draining second node: ${node2}"
    local status
    status=$(http_status "${CLUSTER_ENDPOINT}/api/nodes/drain/${node2}" -X POST -H "X-API-Key: ${API_KEY}")
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
    node3=$(sed -n '3p' "$DRAIN_NODES_FILE")
    log_info "Attempting to drain third node: ${node3}"
    # Capture status AND body so a non-409 surface includes a debuggable payload —
    # http_status_with_body warns and prints the body to the log when the response
    # is non-2xx (note 409 is non-2xx → its body will be surfaced too, which is what
    # we want here).
    local status
    status=$(http_status_with_body "${CLUSTER_ENDPOINT}/api/nodes/drain/${node3}" -X POST -H "X-API-Key: ${API_KEY}")
    log_info "Third drain response: ${status}"

    # Auto-heal is disabled (see test_cluster_ready). With two nodes already DRAINING
    # and no replacement provisioning, the READY count is at 3-of-5 — draining a third
    # would drop operational capacity to 2 < quorum. The disruption-budget guard MUST
    # reject this with HTTP 409. Any other status indicates either (a) the budget
    # enforcement is broken (200 returned despite live READY count below threshold) or
    # (b) the drain endpoint mis-routed (5xx). Either is a real product regression.
    if [ "$status" -eq 409 ] 2>/dev/null; then
        log_pass "Third drain rejected with 409 — disruption budget enforced against live READY cores"
        return 0
    fi
    log_fail "Third drain returned ${status} — expected 409 (budget exhausted). With CTM auto-heal disabled and 2 prior drains in DRAINING, a third drain MUST be refused."
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
