#!/bin/bash
# test-schema-migration.sh — Schema migration + retry
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "${SCRIPT_DIR}/../../lib/common.sh"
source "${SCRIPT_DIR}/../../lib/cluster.sh"

DATASOURCE="${TEST_DATASOURCE:-default}"

test_cluster_ready() {
    wait_for_cluster 60
    log_pass "Cluster ready"
}

# UNTESTABLE without a registered schema-tracked datasource (same as 10-database).
test_schema_status() {
    log_fail "TODO: schema_status('${DATASOURCE}') requires a registered datasource fixture; today returns 500 because the cluster has no datasource named '${DATASOURCE}'"
    return 1
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

# UNTESTABLE without a real datasource fixture: the meaningful assertion is
# "after triggering migration, applied count increased from N to N+1". That
# requires (a) a datasource bound to real Postgres and (b) at least one
# pending migration in the deployed slice. Neither is set up here, so the
# call returns empty/no-op and trivially passes today — exactly the warn-then-pass
# pattern we are eliminating.
test_trigger_migration() {
    log_fail "TODO: bind ${DATASOURCE} to real datasource fixture with pending migration; assert appliedCount increases by 1 after POST /api/schema/migrate"
    return 1
}

# UNTESTABLE without a registered datasource (same reason as test_schema_status above).
test_schema_retry() {
    log_fail "TODO: POST /api/schema/retry/${DATASOURCE} requires a registered datasource fixture (see test_schema_status TODO above)"
    return 1
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
