#!/bin/bash
# test-canary.sh — Canary deployment
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "${SCRIPT_DIR}/../../lib/common.sh"
source "${SCRIPT_DIR}/../../lib/cluster.sh"
source "${SCRIPT_DIR}/../../lib/load.sh"

ARTIFACT_VERSION="${ARTIFACT_VERSION:-1.0.0-alpha}"
NEW_VERSION="${NEW_VERSION:-0.25.1}"
ARTIFACT_BASE="org.pragmatica.aether.example:url-shortener-url-shortener"
LOAD_RPS="${LOAD_RPS:-5}"
MAX_ERROR_RATE="${MAX_ERROR_RATE:-3.0}"

CANARY_ID=""

setup_v2_artifacts() {
    log_info "Uploading v${NEW_VERSION} artifacts for deployment strategy test"

    local project_root
    project_root="$(cd "${SCRIPT_DIR}/../../../.." && pwd)"

    local analytics_jar="${project_root}/examples/url-shortener/target/url-shortener-analytics-${ARTIFACT_VERSION}.jar"
    local shortener_jar="${project_root}/examples/url-shortener/target/url-shortener-url-shortener-${ARTIFACT_VERSION}.jar"
    local blueprint_jar="${project_root}/examples/url-shortener/target/url-shortener-${ARTIFACT_VERSION}-blueprint.jar"

    aether_failover artifact deploy -g org.pragmatica.aether.example -a url-shortener-analytics -v "${NEW_VERSION}" "$analytics_jar" > /dev/null 2>&1
    aether_failover artifact deploy -g org.pragmatica.aether.example -a url-shortener-url-shortener -v "${NEW_VERSION}" "$shortener_jar" > /dev/null 2>&1
    aether_failover artifact deploy -g org.pragmatica.examples -a url-shortener -v "${NEW_VERSION}" "$blueprint_jar" > /dev/null 2>&1

    sleep 10
    log_info "v${NEW_VERSION} artifacts uploaded"
}

test_cluster_ready() {
    wait_for_cluster 60
    wait_for_node_count 5 30
}

test_start_canary() {
    setup_v2_artifacts
    local body="{\"artifactBase\":\"${ARTIFACT_BASE}\",\"version\":\"${NEW_VERSION}\",\"instances\":3,\"initialPercentage\":10}"
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

    # Start light load during promotion (management endpoint)
    start_mgmt_load "$LOAD_RPS" 30 "/api/health"

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
    assert_cluster_healthy "Healthy after full promotion"
}

test_cluster_stable_after_canary() {
    wait_for_node_count 5 30
    wait_for_slices_active 1 60
}

run_test "Cluster ready" test_cluster_ready
run_test "Start canary" test_start_canary
run_test "Canary status" test_canary_status
run_test "Promote to 50%" test_canary_promote_50
run_test "Promote to 100%" test_canary_promote_full
run_test "Cluster stable after canary" test_cluster_stable_after_canary
print_summary
