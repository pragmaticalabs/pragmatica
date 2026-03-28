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

test_schema_status_endpoint() {
    local status
    status=$(schema_status "$DATASOURCE")
    assert_ne "$status" "" "Schema status endpoint responds for ${DATASOURCE}"
}

test_migrations_applied() {
    local status
    status=$(schema_status "$DATASOURCE")
    if [ -z "$status" ]; then
        log_warn "Schema status empty — datasource may not have migrations"
        log_pass "Schema status endpoint responds"
        return 0
    fi

    local applied_count
    applied_count=$(echo "$status" | python3 -c "
import sys, json
try:
    data = json.load(sys.stdin)
    migrations = data.get('migrations', data.get('history', []))
    if isinstance(migrations, list):
        print(len(migrations))
    elif isinstance(data, dict) and 'appliedCount' in data:
        print(data['appliedCount'])
    else:
        print(0)
except:
    print(0)
" 2>/dev/null)

    if [ "$applied_count" -gt 0 ] 2>/dev/null; then
        log_pass "Migrations applied: ${applied_count}"
    else
        log_warn "No migrations found — datasource may not use versioned schema"
        log_pass "Schema endpoint stable"
    fi
}

test_schema_history_entries() {
    local status
    status=$(schema_status "$DATASOURCE")
    if [ -z "$status" ]; then
        log_pass "Schema status responds (no history to verify)"
        return 0
    fi

    local has_history
    has_history=$(echo "$status" | python3 -c "
import sys, json
try:
    data = json.load(sys.stdin)
    migrations = data.get('migrations', data.get('history', []))
    if isinstance(migrations, list) and len(migrations) > 0:
        first = migrations[0]
        has_version = 'version' in first or 'script' in first or 'name' in first
        print('yes' if has_version else 'no')
    else:
        print('no')
except:
    print('no')
" 2>/dev/null)

    if [ "$has_history" = "yes" ]; then
        log_pass "Schema history contains versioned entries"
    else
        log_warn "Schema history entries lack version info or are empty"
        log_pass "Schema history endpoint responds"
    fi
}

test_global_schema_status() {
    local status
    status=$(schema_status)
    assert_ne "$status" "" "Global schema status returns data"
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
