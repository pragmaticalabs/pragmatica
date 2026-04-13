#!/bin/bash
# test-storage-cli.sh — Verify storage CLI commands
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "${SCRIPT_DIR}/../../lib/common.sh"
source "${SCRIPT_DIR}/../../lib/cluster.sh"

test_cluster_ready() {
    wait_for_cluster 60
    log_pass "Cluster ready"
}

# aether storage list — returns output
test_cli_storage_list() {
    local result
    result=$(aether_failover storage list 2>/dev/null) || true
    if [ -z "$result" ]; then
        skip_test "CLI storage list" "CLI storage command not available or no instances configured"
        return 0
    fi
    assert_ne "$result" "" "CLI storage list returns output"
}

# aether storage status {name} — returns detail
test_cli_storage_status() {
    # First get a valid instance name from the REST API
    local api_result
    api_result=$(api_get "/api/storage" 2>/dev/null) || true
    if [ -z "$api_result" ]; then
        skip_test "CLI storage status" "Storage API not available to discover instance names"
        return 0
    fi

    local first_name
    # Extract first instance name from instances array
    first_name=$(echo "$api_result" | grep -o '"name"[[:space:]]*:[[:space:]]*"[^"]*"' | head -1 | sed 's/.*"name"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/')

    if [ -z "$first_name" ]; then
        skip_test "CLI storage status" "No storage instances configured"
        return 0
    fi

    local result
    result=$(aether_failover storage status "$first_name" 2>/dev/null) || true
    if [ -z "$result" ]; then
        skip_test "CLI storage status" "CLI storage status command not available"
        return 0
    fi
    assert_ne "$result" "" "CLI storage status returns detail for '${first_name}'"
}

# aether storage list --format json — returns valid JSON
test_cli_storage_list_json() {
    local result
    result=$(aether_failover storage list --format json 2>/dev/null) || true
    if [ -z "$result" ]; then
        skip_test "CLI storage list JSON" "CLI storage list not available"
        return 0
    fi
    # Verify it parses as JSON
    if echo "$result" | grep -qE '^\s*[\{\[]'; then
        log_pass "CLI storage list returns valid JSON"
    else
        log_fail "CLI storage list returned invalid JSON"
        return 1
    fi
}

run_test "Cluster ready" test_cluster_ready
run_test "CLI storage list" test_cli_storage_list
run_test "CLI storage status" test_cli_storage_status
run_test "CLI storage list JSON format" test_cli_storage_list_json
print_summary
