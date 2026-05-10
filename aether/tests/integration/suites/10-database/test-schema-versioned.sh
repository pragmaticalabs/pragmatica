#!/bin/bash
# test-schema-versioned.sh — Deploy app with versioned migrations, verify applied
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "${SCRIPT_DIR}/../../lib/common.sh"
source "${SCRIPT_DIR}/../../lib/cluster.sh"

DATASOURCE="${TEST_DATASOURCE:-default}"

test_cluster_ready() {
    wait_for_cluster 60
    log_pass "Cluster ready"
}

# UNTESTABLE without a registered schema-tracked datasource: GET /api/schema/status
# returns 500 "Schema status not found for datasource" when no datasource named
# '${DATASOURCE}' is bound. The integration cluster does not register one.
test_schema_status_endpoint() {
    log_fail "TODO: schema_status('${DATASOURCE}') requires a registered datasource fixture; today returns 500 because the cluster has no datasource named '${DATASOURCE}'"
    return 1
}

# UNTESTABLE without a real datasource fixture: this test is meant to assert
# "this datasource has versioned migrations applied". The integration cluster
# does not deploy a slice with bound versioned migrations against a known
# datasource — so even when schema_status() returns successfully, the count
# is legitimately zero and there's nothing to verify. Demoting "no migrations"
# to a pass is the warn-then-pass anti-pattern.
test_migrations_applied() {
    log_fail "TODO: bind to real datasource fixture (deploy a slice with versioned migrations against ${DATASOURCE}) and assert appliedCount > 0"
    return 1
}

# UNTESTABLE for the same reason: without a datasource that actually has
# applied migrations, asserting that history entries carry version/script/name
# fields is asserting on an empty list — passes for the wrong reason.
test_schema_history_entries() {
    log_fail "TODO: requires a datasource with applied versioned migrations to assert history-entry shape (version/script/name fields)"
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
