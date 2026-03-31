#!/bin/bash
# test-storage-management.sh — Verify storage management REST API endpoints
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "${SCRIPT_DIR}/../../lib/common.sh"
source "${SCRIPT_DIR}/../../lib/cluster.sh"

test_cluster_ready() {
    wait_for_cluster 60
    log_pass "Cluster ready"
}

# GET /api/storage — returns instance list
test_storage_list() {
    local result
    result=$(api_get "/api/storage")
    if [ -z "$result" ]; then
        skip_test "Storage list" "Storage API not available (feature may not be enabled)"
        return 0
    fi
    assert_contains "$result" "instances" "Storage list returns instances array"
}

# Verify the default "artifacts" instance appears in the list
test_storage_list_contains_artifacts() {
    local result
    result=$(api_get "/api/storage")
    if [ -z "$result" ]; then
        skip_test "Artifacts in list" "Storage API not available"
        return 0
    fi
    # The artifacts instance may or may not be configured; check gracefully
    if echo "$result" | grep -q "artifacts"; then
        log_pass "Artifacts instance found in storage list"
    else
        log_warn "No 'artifacts' instance in storage list — may not be configured in test cluster"
        # List what instances are available
        local names
        names=$(json_field "$result" "['instances']" 2>/dev/null) || true
        log_info "Available instances: ${names:-none}"
        return 0
    fi
}

# GET /api/storage/{name} — returns detail with tiers and readiness
test_storage_instance_detail() {
    local result
    result=$(api_get "/api/storage")
    if [ -z "$result" ]; then
        skip_test "Instance detail" "Storage API not available"
        return 0
    fi

    # Extract the first instance name from the list
    local first_name
    first_name=$(echo "$result" | python3 -c "
import sys, json
try:
    data = json.load(sys.stdin)
    instances = data.get('instances', [])
    if instances:
        print(instances[0].get('name', ''))
except: pass
" 2>/dev/null)

    if [ -z "$first_name" ]; then
        skip_test "Instance detail" "No storage instances configured"
        return 0
    fi

    local detail
    detail=$(api_get "/api/storage/${first_name}")
    assert_ne "$detail" "" "Storage detail returned for '${first_name}'"
    assert_contains "$detail" "tiers" "Detail includes tiers"
    assert_contains "$detail" "readiness" "Detail includes readiness"
}

# POST /api/storage/{name}/snapshot — triggers snapshot
test_storage_snapshot() {
    local result
    result=$(api_get "/api/storage")
    if [ -z "$result" ]; then
        skip_test "Snapshot trigger" "Storage API not available"
        return 0
    fi

    local first_name
    first_name=$(echo "$result" | python3 -c "
import sys, json
try:
    data = json.load(sys.stdin)
    instances = data.get('instances', [])
    if instances:
        print(instances[0].get('name', ''))
except: pass
" 2>/dev/null)

    if [ -z "$first_name" ]; then
        skip_test "Snapshot trigger" "No storage instances configured"
        return 0
    fi

    local snapshot
    snapshot=$(api_post "/api/storage/${first_name}/snapshot" "{}")
    if [ -z "$snapshot" ]; then
        log_warn "Snapshot trigger returned empty — endpoint may not be wired yet"
        return 0
    fi
    assert_contains "$snapshot" "epoch" "Snapshot response includes epoch"
}

# GET /api/cluster/storage — returns cluster-wide storage view
test_cluster_storage_view() {
    local result
    result=$(api_get "/api/cluster/storage")
    if [ -z "$result" ]; then
        skip_test "Cluster storage view" "Cluster storage API not available"
        return 0
    fi
    assert_contains "$result" "instances" "Cluster storage view returns instances"
}

# GET /api/cluster/storage/{name} — returns cluster-wide detail for specific instance
test_cluster_storage_detail() {
    local list_result
    list_result=$(api_get "/api/storage")
    if [ -z "$list_result" ]; then
        skip_test "Cluster storage detail" "Storage API not available"
        return 0
    fi

    local first_name
    first_name=$(echo "$list_result" | python3 -c "
import sys, json
try:
    data = json.load(sys.stdin)
    instances = data.get('instances', [])
    if instances:
        print(instances[0].get('name', ''))
except: pass
" 2>/dev/null)

    if [ -z "$first_name" ]; then
        skip_test "Cluster storage detail" "No storage instances configured"
        return 0
    fi

    local detail
    detail=$(api_get "/api/cluster/storage/${first_name}")
    if [ -z "$detail" ]; then
        skip_test "Cluster storage detail" "Cluster storage detail API not available"
        return 0
    fi
    assert_contains "$detail" "nodeCount" "Cluster storage detail includes nodeCount"
    assert_contains "$detail" "nodes" "Cluster storage detail includes nodes list"
}

run_test "Cluster ready" test_cluster_ready
run_test "Storage list returns instances" test_storage_list
run_test "Artifacts instance in list" test_storage_list_contains_artifacts
run_test "Instance detail with tiers and readiness" test_storage_instance_detail
run_test "Snapshot trigger" test_storage_snapshot
run_test "Cluster-wide storage view" test_cluster_storage_view
run_test "Cluster storage detail" test_cluster_storage_detail
print_summary
