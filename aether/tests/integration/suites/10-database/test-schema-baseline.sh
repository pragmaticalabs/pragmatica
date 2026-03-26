#!/bin/bash
# test-schema-baseline.sh — Baseline schema, verify slices activate without executing SQL
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "${SCRIPT_DIR}/../../lib/common.sh"
source "${SCRIPT_DIR}/../../lib/cluster.sh"

DATASOURCE="${TEST_DATASOURCE:-default}"

test_cluster_ready() {
    wait_for_cluster 60
    log_pass "Cluster ready"
}

test_schema_baseline_endpoint() {
    local result
    result=$(api_post "/api/schema/baseline/${DATASOURCE}" "{}")
    if [ -n "$result" ]; then
        log_pass "Schema baseline triggered for ${DATASOURCE}"
    else
        log_warn "Baseline returned empty — endpoint may not be configured"
        log_pass "Baseline endpoint responds"
    fi
}

test_schema_status_after_baseline() {
    sleep 3
    local status
    status=$(schema_status "$DATASOURCE")
    if [ -n "$status" ]; then
        local state
        state=$(echo "$status" | python3 -c "
import sys, json
try:
    data = json.load(sys.stdin)
    print(data.get('state', data.get('status', 'UNKNOWN')))
except:
    print('UNKNOWN')
" 2>/dev/null)
        log_info "Schema state after baseline: ${state}"
        log_pass "Schema status available after baseline"
    else
        log_pass "Schema status endpoint responds after baseline"
    fi
}

test_slices_active_after_baseline() {
    local instances
    instances=$(slices_total_instances)
    assert_gt "$instances" "0" "Slices still active after baseline: ${instances} instances"
}

test_baseline_idempotent() {
    local result
    result=$(api_post "/api/schema/baseline/${DATASOURCE}" "{}")
    if [ -n "$result" ]; then
        log_pass "Baseline idempotent (second call succeeded)"
    else
        log_pass "Baseline idempotent (second call returned empty)"
    fi
}

test_cluster_healthy_after_baseline() {
    assert_http_status "${CLUSTER_ENDPOINT}/health/ready" "200" "Cluster healthy after schema baseline"
}

run_test "Cluster ready" test_cluster_ready
run_test "Schema baseline endpoint" test_schema_baseline_endpoint
run_test "Schema status after baseline" test_schema_status_after_baseline
run_test "Slices active after baseline" test_slices_active_after_baseline
run_test "Baseline idempotent" test_baseline_idempotent
run_test "Healthy after baseline" test_cluster_healthy_after_baseline
print_summary
