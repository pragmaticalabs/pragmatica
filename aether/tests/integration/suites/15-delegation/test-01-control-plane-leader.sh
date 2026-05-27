#!/bin/bash
# test-01-control-plane-leader.sh — Verify the LEADER-PINNED control plane.
#
# Rescoped from the former distributed task-group delegation suite. The
# distributed task-assignment machinery (TaskAssignmentCoordinator, the
# `/api/cluster/tasks` + `/api/cluster/tasks/reassign` endpoints, per-group
# METRICS/SCALING/STORAGE/… assignments) was REMOVED. Control-plane components
# (CDM, scaling, streaming, …) are now LEADER-PINNED: they activate on the
# elected cluster leader rather than being assigned to task groups across nodes.
#
# The new contract this suite verifies:
#   1. A single leader is elected and stable.
#   2. The control plane is reachable and functional ON the leader — CDM-managed
#      surfaces (slices, deployments) respond, proving the ControlLoop/CDM is
#      active on the leader.
#
# Leader-handover behaviour (kill the leader → control plane re-pins to the new
# leader) is intentionally NOT exercised here: this suite runs in the parallel,
# non-destructive cluster A, where killing the leader would disrupt co-running
# suites. That path is covered by the destructive, sequential 02-chaos suite
# (test-kill-leader: re-election + control-plane continuity after leader loss).
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "${SCRIPT_DIR}/../../lib/common.sh"
source "${SCRIPT_DIR}/../../lib/cluster.sh"

# ---------------------------------------------------------------------------
# Prerequisite: cluster healthy with a leader (control plane has a home).
# ---------------------------------------------------------------------------
test_cluster_ready() {
    wait_for_cluster_ready 120
    local leader
    leader=$(cluster_leader)
    assert_ne "$leader" "" "Leader elected: ${leader}"
    assert_ne "$leader" "none" "Leader is a real NodeId, not 'none'"
    log_pass "Cluster ready — control plane pinned to leader ${leader}"
}

# ---------------------------------------------------------------------------
# Test: exactly one leader, stable across consecutive gateway reads.
# The mgmt gateway round-robins /api/* across all cores; a split view (two
# backends reporting different leaders) would be the leader-pin failure mode.
# ---------------------------------------------------------------------------
test_single_stable_leader() {
    local probes=$(( ${NODE_COUNT:-5} + 1 ))
    local first lid i
    first=$(cluster_leader 2>/dev/null)
    assert_ne "$first" "" "Leader readable"
    for i in $(seq 2 "$probes"); do
        lid=$(cluster_leader 2>/dev/null)
        if [ "$lid" != "$first" ]; then
            log_fail "Leader view disagreed across backends: read '${first}' then '${lid}' (probe ${i}/${probes}) — leader-pin split view"
            return 1
        fi
    done
    log_pass "Single stable leader '${first}' across ${probes} consecutive gateway reads"
}

# ---------------------------------------------------------------------------
# Test: CDM (slice deployment manager) is functional on the leader.
# CDM is leader-pinned; if the ControlLoop is running, the slices surface it
# manages must respond. Don't `|| echo ""` — that masks CLI failure as a pass.
# ---------------------------------------------------------------------------
test_cdm_functional_on_leader() {
    local slices slices_rc
    slices=$(cluster_slices 2>&1)
    slices_rc=$?
    if [ "$slices_rc" -ne 0 ]; then
        log_fail "cluster_slices CLI failed (rc=${slices_rc}): $(printf '%s' "$slices" | head -c 200)"
        return 1
    fi
    assert_ne "$slices" "" "Slices endpoint responds (CDM active on leader)"
}

# ---------------------------------------------------------------------------
# Test: the deployment-management surface (also leader-pinned) responds.
# ---------------------------------------------------------------------------
test_deployment_surface_functional() {
    local deployments deploy_rc
    deployments=$(deploy_list 2>&1)
    deploy_rc=$?
    if [ "$deploy_rc" -ne 0 ]; then
        log_fail "deploy_list CLI failed (rc=${deploy_rc}): $(printf '%s' "$deployments" | head -c 200)"
        return 1
    fi
    # Steady state may have zero active deployments; a non-error response (any
    # body, including an empty list) proves the leader-pinned deployment manager
    # is serving requests.
    log_pass "Deployment-management surface responds on leader"
}

run_test "Cluster ready" test_cluster_ready
run_test "Single stable leader" test_single_stable_leader
run_test "CDM functional on leader" test_cdm_functional_on_leader
run_test "Deployment surface functional" test_deployment_surface_functional
print_summary
