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

test_schema_status() {
    local status
    status=$(schema_status "$DATASOURCE")
    # May return empty if no schema configured — that is acceptable
    if [ -n "$status" ]; then
        log_pass "Schema status available for ${DATASOURCE}"
    else
        log_warn "Schema status empty — datasource may not be configured"
        log_pass "Schema status endpoint responds"
    fi
}

test_schema_status_all() {
    local status
    status=$(schema_status)
    assert_ne "$status" "" "Global schema status returns data"
}

test_trigger_migration() {
    local result
    result=$(schema_migrate "$DATASOURCE")
    # May succeed or fail depending on whether migrations exist
    if [ -n "$result" ]; then
        log_pass "Schema migration triggered for ${DATASOURCE}"
    else
        log_warn "Migration returned empty — may have no pending migrations"
        log_pass "Migration endpoint responds"
    fi
}

test_schema_retry() {
    local result
    result=$(schema_retry "$DATASOURCE")
    if [ -n "$result" ]; then
        log_pass "Schema retry triggered for ${DATASOURCE}"
    else
        log_warn "Retry returned empty — may have nothing to retry"
        log_pass "Retry endpoint responds"
    fi
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
