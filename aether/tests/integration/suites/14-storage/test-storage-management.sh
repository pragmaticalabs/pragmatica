#!/bin/bash
# test-storage-management.sh — Verify storage management REST API endpoints
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "${SCRIPT_DIR}/../../lib/common.sh"
source "${SCRIPT_DIR}/../../lib/cluster.sh"

test_cluster_ready() {
    wait_for_cluster_ready 60
    log_pass "Cluster ready"
}

# GET /api/v1/storage — returns instance list
test_storage_list() {
    local result
    result=$(api_get "/api/v1/storage")
    if [ -z "$result" ]; then
        skip_test "Storage list" "Storage API not available (feature may not be enabled)"
        return 0
    fi
    # Response may be {} (no storage configured) or {"instances": [...]}
    if echo "$result" | grep -qE '"instances"|^\s*\{\s*\}'; then
        log_pass "Storage list returns valid response"
    else
        log_fail "Storage list returns instances array: unexpected format"
        return 1
    fi
}

# Verify the default "artifacts" instance appears in the list.
#
# Audit 2026-05-21 §2.2 #28: previous form demoted a missing "artifacts" instance to
# log_warn + return 0. The artifacts instance is mandatory — it backs the artifact-repo
# service used by every deploy; its absence is a real product regression that the suite
# must surface, not paper over.
test_storage_list_contains_artifacts() {
    local result
    result=$(api_get "/api/v1/storage")
    if [ -z "$result" ]; then
        log_fail "/api/v1/storage returned empty body — storage management endpoint unreachable"
        return 1
    fi
    # Match the JSON field as emitted by StorageInstanceSummary (`"name":"artifacts"`).
    if ! echo "$result" | grep -qE '"name"[[:space:]]*:[[:space:]]*"artifacts"'; then
        log_fail "/api/v1/storage missing mandatory 'artifacts' instance — artifact-repo service would be broken. Body: $(printf '%s' "$result" | head -c 300)"
        return 1
    fi
    log_pass "/api/v1/storage lists mandatory 'artifacts' instance"
}

# GET /api/v1/storage/{name} — returns detail with tiers and readiness.
#
# Target the mandatory "artifacts" instance directly (per audit 2026-05-21 §1.16: discovery
# via REST + skip-on-empty was a green-sticker). Drop the tautological `assert_ne $detail ""`
# (lint R3, audit §2.1) since `assert_contains` on real fields below is a strictly stronger
# check.
test_storage_instance_detail() {
    local detail
    detail=$(api_get "/api/v1/storage/artifacts")
    if [ -z "$detail" ]; then
        log_fail "/api/v1/storage/artifacts returned empty body"
        return 1
    fi
    assert_contains "$detail" "tiers" "Detail includes tiers"
    assert_contains "$detail" "readiness" "Detail includes readiness"
}

# POST /api/v1/storage/snapshot/{name} — triggers snapshot.
#
# Audit 2026-05-21 §2.2 #26: previous form demoted an empty response body to log_warn +
# return 0. An empty body is "endpoint not wired" — a real product regression, not a
# benign skip. Target the mandatory "artifacts" instance directly and hard-fail on empty.
test_storage_snapshot() {
    local snapshot
    snapshot=$(api_post "/api/v1/storage/snapshot/artifacts" "{}")
    if [ -z "$snapshot" ]; then
        log_fail "POST /api/v1/storage/snapshot/artifacts returned empty body — endpoint not wired or instance missing"
        return 1
    fi
    # SnapshotResponse carries name + epoch + timestampMs. Assert epoch is present.
    assert_contains "$snapshot" "epoch" "Snapshot response includes epoch"
    log_pass "Snapshot of 'artifacts' instance returned: $(printf '%s' "$snapshot" | head -c 200)"
}

# GET /api/v1/cluster/storage — returns cluster-wide storage view
test_cluster_storage_view() {
    local result
    result=$(api_get "/api/v1/cluster/storage")
    if [ -z "$result" ]; then
        skip_test "Cluster storage view" "Cluster storage API not available"
        return 0
    fi
    assert_contains "$result" "instances" "Cluster storage view returns instances"
}

# GET /api/v1/cluster/storage/{name} — returns cluster-wide detail for specific instance
test_cluster_storage_detail() {
    local list_result
    list_result=$(api_get "/api/v1/storage")
    if [ -z "$list_result" ]; then
        skip_test "Cluster storage detail" "Storage API not available"
        return 0
    fi

    local first_name
    first_name=$(echo "$list_result" | (grep -o '"name"[[:space:]]*:[[:space:]]*"[^"]*"' || true) | head -1 | sed 's/.*"name"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/')

    if [ -z "$first_name" ]; then
        skip_test "Cluster storage detail" "No storage instances configured"
        return 0
    fi

    local detail
    detail=$(api_get "/api/v1/cluster/storage/${first_name}")
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
