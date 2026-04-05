#!/bin/bash
# test-reassignment.sh — Verify task reassignment on node failure and operator override
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "${SCRIPT_DIR}/../../lib/common.sh"
source "${SCRIPT_DIR}/../../lib/cluster.sh"

: "${AETHER_SSH_USER:=aether}"

# ---------------------------------------------------------------------------
# Prerequisite: cluster healthy, all tasks active
# ---------------------------------------------------------------------------
test_prerequisite() {
    wait_for_cluster 120
    # Wait for 4 wired groups to be ACTIVE (STORAGE/STREAMING adapters not yet wired)
    wait_for "wired task groups ACTIVE" \
        "[ \$(cluster_tasks | python3 -c \"import sys,json; data=json.load(sys.stdin); print(sum(1 for a in data.get('assignments',[]) if a.get('status')=='ACTIVE'))\" 2>/dev/null) -ge 4 ]" \
        60
    log_pass "Wired task groups active — ready for reassignment tests"
}

# ---------------------------------------------------------------------------
# Test: Operator can force-reassign a task group via API
# ---------------------------------------------------------------------------
test_operator_reassign() {
    local leader
    leader=$(cluster_leader)
    local current_node
    current_node=$(task_group_node "METRICS")
    log_info "METRICS currently on: ${current_node}"

    # Pick a different node
    local target
    target=$(pick_non_leader "$current_node" 1)
    if [ -z "$target" ]; then
        target="$leader"
    fi
    log_info "Reassigning METRICS to: ${target}"

    # Reassign via PUT API
    reassign_task_group "METRICS" "$target"

    # Wait for METRICS to become ACTIVE on new node
    wait_for_task_active "METRICS" 30

    local new_node
    new_node=$(task_group_node "METRICS")
    assert_eq "$new_node" "$target" "METRICS reassigned to ${target}"
}

# ---------------------------------------------------------------------------
# Test: After reassignment, the original assignment was the target
# ---------------------------------------------------------------------------
test_reassignment_status_active() {
    local status
    status=$(task_group_status "METRICS")
    assert_eq "$status" "ACTIVE" "METRICS is ACTIVE after reassignment"
}

# ---------------------------------------------------------------------------
# Test: All other groups unaffected by METRICS reassignment
# ---------------------------------------------------------------------------
test_other_groups_unaffected() {
    for group in SCALING STRATEGIES DEPLOYMENT; do
        local status
        status=$(task_group_status "$group")
        assert_eq "$status" "ACTIVE" "${group} still ACTIVE after METRICS reassignment"
    done
}

# ---------------------------------------------------------------------------
# Test: Node failure triggers automatic reassignment
# (Only run if SSH is available for remote docker operations)
# ---------------------------------------------------------------------------
test_node_failure_reassignment() {
    if [ -z "${AETHER_SSH_KEY:-}" ]; then
        skip_test "Node failure reassignment" "AETHER_SSH_KEY not set — cannot kill containers"
        return 0
    fi

    # Find which node hosts SCALING
    local scaling_node
    scaling_node=$(task_group_node "SCALING")
    log_info "SCALING group on node: ${scaling_node}"

    # Skip if SCALING is on the leader (killing leader triggers re-election, different test)
    local leader
    leader=$(cluster_leader)
    if [ "$scaling_node" = "$leader" ]; then
        log_info "SCALING is on leader — reassigning to non-leader first"
        local alt_node
        alt_node=$(pick_non_leader "$leader" 1)
        reassign_task_group "SCALING" "$alt_node"
        wait_for_task_active "SCALING" 30
        scaling_node="$alt_node"
        log_info "SCALING now on: ${scaling_node}"
    fi

    # Kill the node hosting SCALING
    log_info "Killing node: ${scaling_node}"
    kill_node "$scaling_node"

    # Wait for SCALING to be reassigned to a different node
    sleep 5  # Allow SWIM to detect failure
    wait_for_task_active "SCALING" 60

    local new_node
    new_node=$(task_group_node "SCALING")
    assert_ne "$new_node" "$scaling_node" "SCALING reassigned from dead node ${scaling_node} to ${new_node}"

    local status
    status=$(task_group_status "SCALING")
    assert_eq "$status" "ACTIVE" "SCALING is ACTIVE on new node"

    # Restart the killed node
    log_info "Restarting killed node: ${scaling_node}"
    start_node "$scaling_node"
    wait_for_cluster 120
    log_pass "Cluster recovered after node restart"
}

run_test "Prerequisites" test_prerequisite
run_test "Operator reassign" test_operator_reassign
run_test "Reassignment status ACTIVE" test_reassignment_status_active
run_test "Other groups unaffected" test_other_groups_unaffected
run_test "Node failure reassignment" test_node_failure_reassignment
print_summary
