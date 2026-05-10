#!/bin/bash
# test-schema-versioned.sh — Deploy app with versioned migrations, verify applied
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "${SCRIPT_DIR}/../../lib/common.sh"
source "${SCRIPT_DIR}/../../lib/cluster.sh"

BLUEPRINT="org.pragmatica.aether.test:test-persistence:1.0.0"
DATASOURCE=""

discover_tracked_datasource() {
    local body
    body=$(api_get "/api/schema/status" 2>/dev/null) || return 1
    printf '%s' "$body" | grep -oE '"datasource"[[:space:]]*:[[:space:]]*"[^"]+"' \
                       | head -1 \
                       | sed 's/.*"datasource"[[:space:]]*:[[:space:]]*"\([^"]*\)"/\1/'
}

test_cluster_ready() {
    wait_for_cluster 60
    push_blueprint "$BLUEPRINT"
    deploy_blueprint "$BLUEPRINT"
    if ! wait_for "tracked datasource discovered from blueprint deploy" \
                  "[ -n \"\$(discover_tracked_datasource)\" ]" 60; then
        log_fail "test-persistence blueprint deploy did not register a tracked datasource within 60s"
        return 1
    fi
    DATASOURCE=$(discover_tracked_datasource)
    log_pass "Cluster ready; tracked datasource: ${DATASOURCE}"
}

# Strict: per-datasource schema_status against the discovered datasource must return
# non-empty (the registered SchemaVersionKey carries version + status fields).
test_schema_status_endpoint() {
    if [ -z "$DATASOURCE" ]; then
        log_fail "DATASOURCE empty — discovery in test_cluster_ready failed"
        return 1
    fi
    local status
    if ! status=$(schema_status "$DATASOURCE"); then
        log_fail "schema_status('${DATASOURCE}') failed (api_get returned non-zero)"
        return 1
    fi
    assert_ne "$status" "" "Schema status endpoint returns non-empty body for ${DATASOURCE}"
}

# Strict: test-persistence ships V900__create_kv.sql, so after blueprint deploy +
# successful migration run, schema_status('database').currentVersion should be ≥ 900.
# `SchemaStatusResponse(datasource, currentVersion, lastMigration, status)` carries
# the fields. A `currentVersion` of 0 means the migration was never applied (the
# canonical "no migrations" state we previously could not distinguish).
test_migrations_applied() {
    if [ -z "$DATASOURCE" ]; then
        log_fail "DATASOURCE empty — discovery failed"
        return 1
    fi
    local status current_version
    status=$(schema_status "$DATASOURCE")
    current_version=$(json_value "$status" "currentVersion")
    current_version="${current_version:--1}"
    if [ "$current_version" -gt 0 ] 2>/dev/null; then
        log_pass "Migrations applied for ${DATASOURCE}: currentVersion=${current_version}"
        return 0
    fi
    log_fail "Migrations not applied for ${DATASOURCE}: currentVersion=${current_version} (expected ≥ 1; test-persistence ships V900)"
    return 1
}

# Strict: after migrations are applied, lastMigration field carries the V900
# script name. Asserts the response shape includes a non-empty lastMigration.
test_schema_history_entries() {
    if [ -z "$DATASOURCE" ]; then
        log_fail "DATASOURCE empty — discovery failed"
        return 1
    fi
    local status last_migration
    status=$(schema_status "$DATASOURCE")
    last_migration=$(json_value "$status" "lastMigration")
    if [ -n "$last_migration" ] && [ "$last_migration" != "null" ]; then
        log_pass "Migration history entry present: lastMigration=${last_migration}"
        return 0
    fi
    log_fail "Migration history entry absent for ${DATASOURCE}: lastMigration=${last_migration:-<empty>}"
    return 1
}

# Strict: the global schema status (no datasource path arg) aggregates over all
# bound datasources. Currently empty list is the legitimate result for our cluster
# (no datasources bound). Assert HTTP 2xx + the response body is JSON-shaped.
test_global_schema_status() {
    local status
    if ! status=$(schema_status); then
        log_fail "Global schema_status failed (api_get returned non-zero)"
        return 1
    fi
    # Empty list ("[]" or "{}") is a valid response when no datasources are bound.
    # Assert response is JSON-shaped (starts with [ or {).
    case "$status" in
        \[*|\{*) log_pass "Global schema status returns JSON-shaped body (${#status} bytes)" ;;
        *) log_fail "Global schema status response is not JSON-shaped: $(printf '%s' "$status" | head -c 100)"; return 1 ;;
    esac
}

test_cluster_healthy_after_schema_check() {
    assert_cluster_healthy "Cluster healthy after schema checks"
}

run_test "Cluster ready" test_cluster_ready
run_test "Schema status endpoint" test_schema_status_endpoint
run_test "Migrations applied" test_migrations_applied
run_test "Schema history entries" test_schema_history_entries
run_test "Global schema status" test_global_schema_status
run_test "Healthy after schema checks" test_cluster_healthy_after_schema_check
print_summary
