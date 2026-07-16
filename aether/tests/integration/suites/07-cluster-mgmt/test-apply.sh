#!/bin/bash
# test-apply.sh — Config apply, verify convergence
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "${SCRIPT_DIR}/../../lib/common.sh"
source "${SCRIPT_DIR}/../../lib/cluster.sh"

test_cluster_ready() {
    wait_for_cluster_ready 60
    log_pass "Cluster ready"
}

test_get_current_config() {
    local config
    config=$(config_export)
    assert_ne "$config" "" "Current config retrievable"
}

test_apply_config_override() {
    # /api/config expects {key, value, nodeId?} per ConfigRoutes.SetConfigRequest.
    # The earlier {overrides:{...}} shape always 500s with "Missing key or value field".
    local body='{"key":"test.integration.marker","value":"applied"}'
    local result
    result=$(config_apply "$body")
    if [ -n "$result" ]; then
        log_pass "Config apply returned response"
    else
        log_fail "Config apply returned empty for {key,value} body"
        return 1
    fi
}

test_config_converges() {
    # Give cluster time to propagate
    sleep 5
    assert_cluster_healthy "Cluster healthy after config apply"
}

test_config_visible_on_all_nodes() {
    # Audit fix (#13): the prior shape called config_export twice on
    # CLUSTER_ENDPOINT and never probed individual nodes — "all nodes" was a lie.
    # Now we probe each node's direct management port (MGMT_PORT + offset) and
    # assert the marker key applied by test_apply_config_override is visible
    # everywhere with the expected value. Any disagreement → hard fail.
    local expected_key="test.integration.marker"
    local expected_value="applied"
    local mismatches=0
    local i
    for i in $(seq 0 $((NODE_COUNT - 1))); do
        local node_name="node-$((i + 1))"
        local node_ep
        node_ep=$(node_base_url "$i" 2>/dev/null || echo "node-${i}")
        local overrides
        if ! overrides=$(node_api_get "$i" "/api/config/overrides"); then
            log_fail "Per-node config probe failed on ${node_name} (${node_ep})"
            mismatches=$((mismatches + 1))
            continue
        fi
        if [ -z "$overrides" ]; then
            log_fail "${node_name} returned empty overrides body"
            mismatches=$((mismatches + 1))
            continue
        fi
        local actual
        actual=$(json_value "$overrides" "$expected_key")
        if [ "$actual" = "$expected_value" ]; then
            log_pass "${node_name} sees ${expected_key}=${expected_value}"
        else
            log_fail "${node_name} expected ${expected_key}='${expected_value}', got '${actual}'"
            mismatches=$((mismatches + 1))
        fi
    done
    if [ "$mismatches" -gt 0 ]; then
        log_fail "Config not visible on all nodes (${mismatches}/${NODE_COUNT} disagreed)"
        return 1
    fi
    log_pass "Config marker visible on all ${NODE_COUNT} nodes"
}

test_overrides_endpoint() {
    local overrides
    overrides=$(api_get "/api/config/overrides")
    # May be empty if no overrides configured
    if [ -n "$overrides" ]; then
        log_pass "Config overrides endpoint returns data"
    else
        log_pass "Config overrides endpoint responds (empty is valid)"
    fi
}

test_cluster_unchanged() {
    local count
    count=$(cluster_member_count)
    assert_eq "$count" "5" "Node count unchanged after config apply"
}

run_test "Cluster ready" test_cluster_ready
run_test "Get current config" test_get_current_config
run_test "Apply config override" test_apply_config_override
run_test "Config converges" test_config_converges
run_test "Config visible on all nodes" test_config_visible_on_all_nodes
run_test "Overrides endpoint" test_overrides_endpoint
run_test "Cluster unchanged" test_cluster_unchanged
print_summary
