#!/bin/bash
# test-02-reassignment.sh — Verify task reassignment on node failure and operator override
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "${SCRIPT_DIR}/../../lib/common.sh"
source "${SCRIPT_DIR}/../../lib/cluster.sh"

: "${AETHER_SSH_USER:=aether}"

# ---------------------------------------------------------------------------
# Helper: get a cluster node different from the given node ID
# Queries the actual cluster status instead of hardcoded node names.
# ---------------------------------------------------------------------------
get_different_node() {
    local exclude="$1"
    local tasks_json
    tasks_json=$(cluster_tasks)
    echo "$tasks_json" | grep -o '"assignedTo"[[:space:]]*:[[:space:]]*"[^"]*"' | sed 's/.*"\([^"]*\)"$/\1/' | while IFS= read -r node; do
        if [ -n "$node" ] && [ "$node" != "$exclude" ]; then
            echo "$node"
            break
        fi
    done
}

# ---------------------------------------------------------------------------
# Prerequisite: cluster healthy, all tasks active
# ---------------------------------------------------------------------------
test_prerequisite() {
    wait_for_cluster 120
    wait_for "all task groups ACTIVE" \
        "[ \$(cluster_tasks | grep -o '\"status\"[[:space:]]*:[[:space:]]*\"ACTIVE\"' | wc -l | tr -d ' ') -ge 6 ]" \
        60
    log_pass "All task groups active — ready for reassignment tests"
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

    # Pick a different node from the actual cluster
    local target
    target=$(get_different_node "$current_node")
    if [ -z "$target" ]; then
        target="$leader"
    fi
    log_info "Reassigning METRICS to: ${target}"

    # Reassign via PUT API
    reassign_task_group "METRICS" "$target"

    # Wait for METRICS to become ACTIVE on new node — must check BOTH target node
    # AND ACTIVE status, otherwise the stale ACTIVE entry from the prior assignment
    # satisfies the predicate immediately and we read the old node back.
    wait_for_task_assigned "METRICS" "$target" 30

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
    for group in SCALING STRATEGIES DEPLOYMENT STORAGE STREAMING; do
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
        alt_node=$(get_different_node "$leader")
        reassign_task_group "SCALING" "$alt_node"
        wait_for_task_assigned "SCALING" "$alt_node" 30
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
