#!/bin/bash
# test-task-assignments.sh — Verify control plane task delegation
# Tests: assignment creation, status tracking, distribution, API
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "${SCRIPT_DIR}/../../lib/common.sh"
source "${SCRIPT_DIR}/../../lib/cluster.sh"

# ---------------------------------------------------------------------------
# Prerequisite: cluster must be healthy with a leader
# ---------------------------------------------------------------------------
test_cluster_ready() {
    wait_for_cluster_ready 120
    local leader
    leader=$(cluster_leader)
    assert_ne "$leader" "" "Leader elected: ${leader}"
    # Wait for task group assignments to land via consensus before downstream tests
    wait_for_all_tasks_active 60 || log_warn "task assignments not all ACTIVE within 60s — proceeding anyway"
}

# ---------------------------------------------------------------------------
# Test: /api/cluster/tasks endpoint returns data
# ---------------------------------------------------------------------------
test_tasks_api_returns_data() {
    local tasks
    tasks=$(cluster_tasks)
    assert_ne "$tasks" "" "Tasks API returns non-empty response"
    assert_contains "$tasks" "assignments" "Response contains 'assignments' field"
}

# ---------------------------------------------------------------------------
# Test: All 6 task groups have assignments
# ---------------------------------------------------------------------------
test_all_groups_assigned() {
    local count
    count=$(task_assignment_count)
    assert_ge "$count" "6" "At least 6 task groups assigned (got ${count})"
}

# ---------------------------------------------------------------------------
# Test: All task groups reach ACTIVE status
# ---------------------------------------------------------------------------
test_all_groups_active() {
    local timeout=60
    wait_for "all task groups ACTIVE" \
        "[ \$(cluster_tasks | grep -o '\"status\"[[:space:]]*:[[:space:]]*\"ACTIVE\"' | wc -l | tr -d ' ') -ge 6 ]" \
        "$timeout"

    for group in METRICS SCALING STRATEGIES DEPLOYMENT STORAGE STREAMING; do
        local status
        status=$(task_group_status "$group")
        assert_eq "$status" "ACTIVE" "Task group ${group} is ACTIVE"
    done
}

# ---------------------------------------------------------------------------
# Test: Tasks are distributed across nodes (not all on leader)
# ---------------------------------------------------------------------------
test_tasks_distributed() {
    # Audit 2026-05-21 §1.17 / §2.2 #27: prior assertion was `>=1`, which is a
    # vacuous tautology — any non-empty assignments map satisfies it, including
    # the leader-only failure mode this test is supposed to catch. Strict check
    # now requires distribution across ≥2 distinct nodes.
    local node_count="${NODE_COUNT:-5}"
    if [ "$node_count" -lt 2 ]; then
        skip_test "Tasks distributed" "NODE_COUNT=${node_count} < 2 — distribution check requires multi-node cluster"
        return 0
    fi

    local tasks
    tasks=$(cluster_tasks)
    local assigned_nodes unique_nodes
    assigned_nodes=$(echo "$tasks" | grep -o '"assignedTo"[[:space:]]*:[[:space:]]*"[^"]*"' | sed 's/.*"\([^"]*\)"$/\1/' | sort -u)
    unique_nodes=$(printf '%s\n' "$assigned_nodes" | grep -c -v '^$' || echo "0")

    # Leader-only failure mode: surface diagnostic before the assertion fires so
    # the failure message names the culprit instead of just the count.
    if [ "$unique_nodes" = "1" ]; then
        local sole_node leader
        sole_node=$(printf '%s' "$assigned_nodes" | head -n1)
        leader=$(cluster_leader 2>/dev/null || echo "<unknown>")
        log_warn "All task groups assigned to ${sole_node} (leader=${leader}); expected distribution across multiple nodes"
    fi

    # With 5 nodes and 6 groups, the CDM should redistribute beyond the leader
    # within wait_for_all_tasks_active. ≥2 catches the all-on-leader anti-pattern.
    assert_ge "$unique_nodes" "2" "Task groups distributed across at least 2 distinct nodes (got ${unique_nodes})"
}

# ---------------------------------------------------------------------------
# Test: Each group has an assigned node that is in the cluster
# ---------------------------------------------------------------------------
test_assignments_point_to_valid_nodes() {
    local tasks
    tasks=$(cluster_tasks)
    local invalid
    # Check if any assignment has an empty assignedTo — look for "assignedTo":""
    invalid=""
    if echo "$tasks" | grep -q '"assignedTo"[[:space:]]*:[[:space:]]*""'; then
        # Extract group names of unassigned tasks
        invalid=$(echo "$tasks" | grep -B5 '"assignedTo"[[:space:]]*:[[:space:]]*""' | grep -o '"group"[[:space:]]*:[[:space:]]*"[^"]*"' | sed 's/.*"\([^"]*\)"$/\1/')
    fi
    assert_eq "$invalid" "" "All assignments point to valid nodes"
}

# ---------------------------------------------------------------------------
# Test: DEPLOYMENT group has the CDM components
# ---------------------------------------------------------------------------
test_deployment_group_functional() {
    # If DEPLOYMENT is ACTIVE, CDM should be functional — verify by checking
    # that the slices endpoint works (CDM manages slice deployment state)
    local status
    status=$(task_group_status "DEPLOYMENT")
    assert_eq "$status" "ACTIVE" "DEPLOYMENT group is ACTIVE"

    # CDM functionality check: slices endpoint should respond. Don't `|| echo ""`
    # — that masks CLI failure as a passing path. Capture stderr + rc and surface
    # the real error.
    local slices slices_rc
    slices=$(cluster_slices 2>&1)
    slices_rc=$?
    if [ "$slices_rc" -ne 0 ]; then
        log_fail "cluster_slices CLI failed (rc=${slices_rc}): $(printf '%s' "$slices" | head -c 200)"
        return 1
    fi
    assert_ne "$slices" "" "Slices endpoint responds (CDM functional)"
}

# ---------------------------------------------------------------------------
# Test: METRICS group functional — metrics collection should be running
# ---------------------------------------------------------------------------
test_metrics_group_functional() {
    local status
    status=$(task_group_status "METRICS")
    assert_eq "$status" "ACTIVE" "METRICS group is ACTIVE"

    # Metrics node in the assignment should be collecting
    local metrics_node
    metrics_node=$(task_group_node "METRICS")
    assert_ne "$metrics_node" "" "METRICS assigned to node: ${metrics_node}"
}

run_test "Cluster ready" test_cluster_ready
run_test "Tasks API returns data" test_tasks_api_returns_data
run_test "All groups assigned" test_all_groups_assigned
run_test "All groups active" test_all_groups_active
run_test "Tasks distributed" test_tasks_distributed
run_test "Assignments point to valid nodes" test_assignments_point_to_valid_nodes
run_test "DEPLOYMENT group functional" test_deployment_group_functional
run_test "METRICS group functional" test_metrics_group_functional
print_summary
