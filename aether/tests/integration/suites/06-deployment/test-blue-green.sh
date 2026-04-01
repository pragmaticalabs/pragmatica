#!/bin/bash
# test-blue-green.sh — Blue-green deployment switchover
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "${SCRIPT_DIR}/../../lib/common.sh"
source "${SCRIPT_DIR}/../../lib/cluster.sh"
source "${SCRIPT_DIR}/../../lib/load.sh"

ARTIFACT_VERSION="${ARTIFACT_VERSION:-0.25.0}"
NEW_VERSION="${NEW_VERSION:-0.25.1}"
ARTIFACT_BASE="org.pragmatica.aether.example:url-shortener-url-shortener"
LOAD_RPS="${LOAD_RPS:-5}"
MAX_ERROR_RATE="${MAX_ERROR_RATE:-3.0}"

DEPLOYMENT_ID=""

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

test_start_blue_green() {
    setup_v2_artifacts
    local body="{\"artifactBase\":\"${ARTIFACT_BASE}\",\"version\":\"${NEW_VERSION}\",\"instances\":3}"
    local result
    result=$(blue_green_deploy "$body")
    assert_ne "$result" "" "Blue-green deployment started"

    DEPLOYMENT_ID=$(echo "$result" | python3 -c "
import sys, json
data = json.load(sys.stdin)
for field in ['deploymentId', 'id']:
    if field in data:
        print(data[field])
        sys.exit(0)
print('')
" 2>/dev/null)
    log_info "Deployment ID: ${DEPLOYMENT_ID}"
}

test_blue_green_status() {
    if [ -z "$DEPLOYMENT_ID" ]; then
        log_fail "No deployment ID"
        return 1
    fi
    local status
    status=$(blue_green_status "$DEPLOYMENT_ID")
    assert_ne "$status" "" "Blue-green status available"
}

test_switch_under_load() {
    if [ -z "$DEPLOYMENT_ID" ]; then
        log_fail "No deployment ID"
        return 1
    fi

    # Start load during switch (management endpoint)
    start_mgmt_load "$LOAD_RPS" 60 "/api/health"
    sleep 5

    log_info "Switching traffic to green"
    blue_green_switch "$DEPLOYMENT_ID"
    sleep 10

    for pid in "${LOAD_PIDS[@]}"; do
        wait "$pid" 2>/dev/null || true
    done
    local result
    result=$(stop_load)
    assert_error_rate_below "$result" "$MAX_ERROR_RATE" "Switch error rate < ${MAX_ERROR_RATE}%"
}

test_switch_back() {
    if [ -z "$DEPLOYMENT_ID" ]; then
        log_fail "No deployment ID"
        return 1
    fi

    log_info "Switching back to blue"
    blue_green_switch_back "$DEPLOYMENT_ID"
    sleep 10
    assert_cluster_healthy "Healthy after switch-back"
}

test_complete_deployment() {
    if [ -z "$DEPLOYMENT_ID" ]; then
        log_fail "No deployment ID"
        return 1
    fi

    # Switch to green again and complete
    blue_green_switch "$DEPLOYMENT_ID"
    sleep 5
    blue_green_complete "$DEPLOYMENT_ID"
    sleep 5
    assert_cluster_healthy "Healthy after complete"
}

test_cluster_stable() {
    wait_for_node_count 5 30
}

run_test "Cluster ready" test_cluster_ready
run_test "Start blue-green" test_start_blue_green
run_test "Blue-green status" test_blue_green_status
run_test "Switch under load" test_switch_under_load
run_test "Switch back" test_switch_back
run_test "Complete deployment" test_complete_deployment
run_test "Cluster stable" test_cluster_stable
print_summary
