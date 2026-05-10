#!/bin/bash
# test-schema-migration.sh — Schema migration + retry
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

# Strict: per-datasource schema_status against the discovered datasource.
test_schema_status() {
    if [ -z "$DATASOURCE" ]; then
        log_fail "DATASOURCE empty — discovery in test_cluster_ready failed"
        return 1
    fi
    local status
    if ! status=$(schema_status "$DATASOURCE"); then
        log_fail "schema_status('${DATASOURCE}') failed"
        return 1
    fi
    assert_ne "$status" "" "Schema status non-empty for ${DATASOURCE}"
}

# Strict: global schema status aggregates over all bound datasources. Empty list
# is a valid result when no datasources are bound; assert JSON-shaped response.
test_schema_status_all() {
    local status
    if ! status=$(schema_status); then
        log_fail "Global schema_status failed"
        return 1
    fi
    case "$status" in
        \[*|\{*) log_pass "Global schema status returns JSON-shaped body (${#status} bytes)" ;;
        *) log_fail "Global schema status response is not JSON-shaped: $(printf '%s' "$status" | head -c 100)"; return 1 ;;
    esac
}

# Strict (steady-state): after blueprint deploy, test-persistence's V900
# migration is applied automatically (no separate /api/schema/migrate trigger
# needed). Assert currentVersion ≥ 900 — proves the migration ran. Triggering
# an explicit migrate against an already-migrated datasource is a no-op so
# we test the steady-state outcome.
test_trigger_migration() {
    if [ -z "$DATASOURCE" ]; then
        log_fail "DATASOURCE empty — discovery failed"
        return 1
    fi
    local status current_version
    status=$(schema_status "$DATASOURCE")
    current_version=$(json_value "$status" "currentVersion")
    current_version="${current_version:--1}"
    if [ "$current_version" -ge 900 ] 2>/dev/null; then
        log_pass "Migration applied: currentVersion=${current_version} (V900__create_kv.sql ran)"
        return 0
    fi
    log_fail "Migration not applied: currentVersion=${current_version} (expected ≥ 900)"
    return 1
}

test_schema_retry() {
    if [ -z "$DATASOURCE" ]; then
        log_fail "DATASOURCE empty — discovery failed"
        return 1
    fi
    if ! schema_retry "$DATASOURCE" >/dev/null; then
        log_fail "POST /api/schema/retry/${DATASOURCE} failed"
        return 1
    fi
    log_pass "Schema retry endpoint accepts call for ${DATASOURCE}"
}

test_cluster_healthy_after_migration() {
    assert_cluster_healthy "Cluster healthy after schema operations"
}

run_test "Cluster ready" test_cluster_ready
run_test "Schema status" test_schema_status
run_test "Global schema status" test_schema_status_all
run_test "Trigger migration" test_trigger_migration
run_test "Schema retry" test_schema_retry
run_test "Healthy after migration" test_cluster_healthy_after_migration
print_summary
