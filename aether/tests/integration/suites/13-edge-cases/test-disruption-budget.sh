#!/bin/bash
# test-disruption-budget.sh — Drain beyond the quorum budget, verify rejection
#
# Drain admission order is quorum budget -> readiness -> slice floor (#1720); `force=true`
# reaches only the slice-floor guard (NodeLifecycleRoutes). Every drain here passes
# force=true so ONLY the quorum budget is exercised: a 409 from the slice floor (which
# depends on where slices happen to be placed) can then never stand in for a budget
# rejection. Expected: 200, 200, 409 with the "Disruption budget exceeded" body.
# The unforced slice-floor 409 ("minAvailable") is deliberately not asserted here: it needs
# a hosted slice on the drained node, which this suite does not deploy, so it is not
# deterministic.
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

# POST a forced drain (quorum budget only; see header). Prints the HTTP status; the response
# body is left in <body_file> so callers can tell the budget 409 from any other 409.
_forced_drain() {
    local node="$1" body_file="$2"
    curl -sk -o "$body_file" -w "%{http_code}" -m 10 \
         -X POST -H "X-API-Key: ${API_KEY}" \
         "${CLUSTER_ENDPOINT}/api/v1/nodes/drain/${node}?force=true"
}

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
    local leader drain_count
    leader=$(cluster_leader)
    if ! pick_non_leader "$leader" 3 > "$DRAIN_NODES_FILE"; then
        log_fail "Cluster ready: pick_non_leader failed — need 3 distinct live READY non-leader nodes for the drain sequence"
        return 1
    fi
    # grep -c prints 0 AND exits 1 on zero matches — `|| echo 0` would emit a second
    # line ("0\n0") and wedge the numeric test with rc=2 (silently falsy under ||).
    drain_count=$(grep -cve '^$' "$DRAIN_NODES_FILE" 2>/dev/null || true)
    if [ "${drain_count:-0}" -lt 3 ]; then
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
    # Re-resolve CLUSTER_ENDPOINT to a live node (#126 pattern) — drains must not 000
    # against a port whose container died earlier in this destructive suite.
    _refresh_mgmt_entry_point || log_warn "no live mgmt endpoint — proceeding with pinned ${CLUSTER_ENDPOINT}"

    # Diagnostic: surface auto-heal state and current ON_DUTY count so a 503
    # rejection has a debuggable trail. Without these the only signal is "503"
    # which doesn't distinguish (a) auto-heal-disabled-but-budget-tripped from
    # (b) auto-heal-still-active from (c) cluster degraded from a prior suite.
    local autoheal_state
    autoheal_state=$(api_get "/api/v1/cluster/topology/auto-heal" 2>/dev/null || echo "<unreachable>")
    log_info "auto-heal state before first drain: $(printf '%s' "$autoheal_state" | head -c 200)"

    local status body_file
    body_file=$(mktemp)
    status=$(_forced_drain "$node1" "$body_file")

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
    # Re-resolve to a live node — see test_drain_first_node_allowed.
    _refresh_mgmt_entry_point || log_warn "no live mgmt endpoint — proceeding with pinned ${CLUSTER_ENDPOINT}"
    local status body_file body
    body_file=$(mktemp)
    status=$(_forced_drain "$node2" "$body_file")
    body=$(head -c 500 < "$body_file" | tr '\n' ' ')
    rm -f "$body_file"
    log_info "Second drain response: ${status}"
    # Auto-heal is disabled (see test_cluster_ready) and force=true bypasses the slice floor, so
    # the quorum budget alone decides: 5 core nodes minus 2 drained leaves 3 = quorum, still
    # admitted. Any 409 here is a real budget miscount, not a race with auto-heal.
    if [ "$status" -ge 200 ] && [ "$status" -lt 300 ] 2>/dev/null; then
        log_pass "Second drain accepted (${status}) — within budget (3 of 5 remain)"
    else
        log_fail "Second drain returned ${status} — expected 2xx (3 of 5 cores remain, auto-heal disabled, slice floor forced past); body=${body}"
        return 1
    fi
}

test_drain_beyond_budget_rejected() {
    local node3
    node3=$(sed -n '3p' "$DRAIN_NODES_FILE")
    log_info "Attempting to drain third node: ${node3}"
    # Re-resolve to a live node — see test_drain_first_node_allowed.
    _refresh_mgmt_entry_point || log_warn "no live mgmt endpoint — proceeding with pinned ${CLUSTER_ENDPOINT}"
    local status body_file body
    body_file=$(mktemp)
    status=$(_forced_drain "$node3" "$body_file")
    body=$(head -c 500 < "$body_file" | tr '\n' ' ')
    rm -f "$body_file"
    log_info "Third drain response: ${status} body=${body}"

    # Auto-heal is disabled (see test_cluster_ready). With two nodes already DRAINING and no
    # replacement provisioning, a third drain would leave 2 core nodes < quorum 3, so the quorum
    # budget MUST refuse it with 409. force=true bypasses the slice floor, so the 409 can only
    # be the budget — and the body proves it: a status-only check cannot tell it from the
    # placement-dependent slice-floor 409 ("minAvailable").
    if [ "$status" -eq 409 ] 2>/dev/null && printf '%s' "$body" | grep -qF "Disruption budget exceeded" \
        && printf '%s' "$body" | grep -qF "minimum is 3"; then
        log_pass "Third drain rejected with 409 — quorum budget enforced (Disruption budget exceeded, minimum is 3)"
        return 0
    fi
    log_fail "Third drain returned ${status} body=${body} — expected 409 with the quorum-budget body ('Disruption budget exceeded ... minimum is 3'). With auto-heal disabled and 2 prior drains in DRAINING, a forced third drain MUST be refused by the budget, not the slice floor."
    return 1
}

test_quorum_preserved() {
    assert_cluster_healthy "Quorum preserved despite drains"
}

test_reactivate_nodes() {
    # Attempt reactivation for API-behavior visibility — but DrainProcedure is
    # single-shot (INACTIVE → DRAINING → EXITED, no abort path): an accepted drain
    # ALWAYS ends in process exit within its ~30s grace, regardless of lifecycle
    # reactivation. Bit Hetzner gate-A1: "healthy after reactivation" passed, then
    # both drained cores exited ~1 min later and the next script started on a
    # collapsing cluster. This cleanup therefore OWNS the aftermath: expect the two
    # drained nodes to exit, re-enable auto-heal, and barrier until the core count
    # is restored before the suite proceeds. (Product follow-up filed separately:
    # lifecycle activate on an exit-committed node should 409, not report success.)
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
    # Drained nodes (targets 1 and 2; target 3 was 409-rejected and never drained)
    # WILL exit — wait for their containers to leave RUNNING instead of pretending
    # reactivation saved them.
    #
    # Cloud: there is no shared docker daemon to `docker ps` against — a drained node
    # that has exited simply drops out of the cluster's READY membership. Use
    # cloud_running_cores (mgmt API /api/v1/nodes/lifecycle) as the liveness source there;
    # the drained ids are runtime node-ids (pick_non_leader returns runtime ids on
    # cloud), so match them against the membership READY set.
    local drained_1 drained_2 running deadline still_running
    drained_1=$(sed -n '1p' "$DRAIN_NODES_FILE")
    drained_2=$(sed -n '2p' "$DRAIN_NODES_FILE")
    deadline=$((SECONDS + 120))
    while [ "$SECONDS" -lt "$deadline" ]; do
        if [ "${CLOUD_MODE:-false}" = "true" ]; then
            running=$(cloud_running_cores)
        else
            running=$(remote_exec "docker ps --format '{{.Names}}'")
        fi
        still_running=""
        echo "$running" | grep -qx "$drained_1" && still_running="$drained_1"
        echo "$running" | grep -qx "$drained_2" && still_running="${still_running} ${drained_2}"
        [ -z "$still_running" ] && break
        sleep 3
    done
    if [ -n "$still_running" ]; then
        log_info "Drained node(s) still running after 120s: ${still_running} (drain grace longer than expected — proceeding)"
    else
        log_info "Both drained nodes exited as DrainProcedure commits (${drained_1}, ${drained_2})"
    fi
    # Re-enable auto-heal now (idempotent with the EXIT trap) so replacements provision,
    # then barrier until the core count is actually restored — the next script must
    # start on a settled cluster, not a healing one.
    enable_auto_heal || log_warn "enable_auto_heal failed in cleanup — EXIT trap will retry"
    wait_for_node_count 5 240 || {
        log_fail "Core count did not recover to 5 within 240s after drain exits + auto-heal"
        return 1
    }
    await_generation_quiesced "$CLUSTER_ENDPOINT" "current" 60 || log_warn "post-recovery quiesce not reached"
    assert_cluster_healthy "Cluster healthy after drain aftermath (replacements settled)"
}

run_test "Cluster ready (5 nodes)" test_cluster_ready
run_test "First drain allowed" test_drain_first_node_allowed
run_test "Second drain allowed" test_drain_second_node_allowed
run_test "Third drain rejected (quorum budget)" test_drain_beyond_budget_rejected
run_test "Quorum preserved" test_quorum_preserved
run_test "Reactivate nodes" test_reactivate_nodes
print_summary
