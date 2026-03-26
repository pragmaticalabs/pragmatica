#!/bin/bash
# test-canary.sh — Canary deployment
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "${SCRIPT_DIR}/../../lib/common.sh"
source "${SCRIPT_DIR}/../../lib/cluster.sh"
source "${SCRIPT_DIR}/../../lib/load.sh"

CANARY_ARTIFACT="${CANARY_ARTIFACT:-url-shortener}"
LOAD_RPS="${LOAD_RPS:-5}"
MAX_ERROR_RATE="${MAX_ERROR_RATE:-3.0}"

CANARY_ID=""

test_cluster_ready() {
    wait_for_cluster 60
    assert_eq "$(cluster_node_count)" "5" "5 nodes ready"
}

test_start_canary() {
    local body="{\"artifact\":\"${CANARY_ARTIFACT}\",\"initialPercentage\":10}"
    local result
    result=$(canary_start "$body")
    assert_ne "$result" "" "Canary started"

    CANARY_ID=$(echo "$result" | python3 -c "
import sys, json
data = json.load(sys.stdin)
for field in ['canaryId', 'id', 'deploymentId']:
    if field in data:
        print(data[field])
        sys.exit(0)
print('')
" 2>/dev/null)
    log_info "Canary ID: ${CANARY_ID}"
}

test_canary_status() {
    if [ -z "$CANARY_ID" ]; then
        log_fail "No canary ID — cannot check status"
        return 1
    fi
    local status
    status=$(canary_status "$CANARY_ID")
    assert_ne "$status" "" "Canary status available"
}

test_canary_promote_50() {
    if [ -z "$CANARY_ID" ]; then
        log_fail "No canary ID"
        return 1
    fi

    # Start light load during promotion
    start_load "$LOAD_RPS" 30 "GET" "/api/health"

    canary_promote "$CANARY_ID" 50
    log_info "Promoted canary to 50%"
    sleep 10

    for pid in "${LOAD_PIDS[@]}"; do
        wait "$pid" 2>/dev/null || true
    done
    local result
    result=$(stop_load)
    assert_error_rate_below "$result" "$MAX_ERROR_RATE" "Canary promote error rate < ${MAX_ERROR_RATE}%"
}

test_canary_promote_full() {
    if [ -z "$CANARY_ID" ]; then
        log_fail "No canary ID"
        return 1
    fi
    canary_promote_full "$CANARY_ID"
    log_info "Promoted canary to 100%"
    sleep 10
    assert_http_status "${CLUSTER_ENDPOINT}/health/ready" "200" "Healthy after full promotion"
}

test_cluster_stable_after_canary() {
    local count
    count=$(cluster_node_count)
    assert_eq "$count" "5" "5 nodes after canary"
    wait_for_slices_active 1 60
}

run_test "Cluster ready" test_cluster_ready
run_test "Start canary" test_start_canary
run_test "Canary status" test_canary_status
run_test "Promote to 50%" test_canary_promote_50
run_test "Promote to 100%" test_canary_promote_full
run_test "Cluster stable after canary" test_cluster_stable_after_canary
print_summary
