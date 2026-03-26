#!/bin/bash
# test-slice-deployment.sh — Deploy url-shortener blueprint, verify requests
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "${SCRIPT_DIR}/../../lib/common.sh"
source "${SCRIPT_DIR}/../../lib/cluster.sh"

BLUEPRINT_ARTIFACT="${TEST_BLUEPRINT:-url-shortener}"

test_deploy_blueprint() {
    local result
    result=$(deploy_blueprint "$BLUEPRINT_ARTIFACT")
    assert_ne "$result" "" "Blueprint deploy returned response"
}

test_slices_provisioned() {
    wait_for_slices_active 1 120
    local instances
    instances=$(slices_total_instances)
    assert_gt "$instances" "0" "Slices have active instances: ${instances}"
}

test_blueprint_listed() {
    local blueprints
    blueprints=$(list_blueprints)
    assert_contains "$blueprints" "$BLUEPRINT_ARTIFACT" "Blueprint visible in list"
}

test_app_endpoint_reachable() {
    wait_for "app endpoint ready" "curl -sf ${APP_ENDPOINT}/api/health > /dev/null 2>&1" 60
    assert_http_status "${APP_ENDPOINT}/api/health" "200" "App health endpoint returns 200"
}

test_app_request_succeeds() {
    # Simple GET to verify the deployed app is serving
    local status
    status=$(http_status "${APP_ENDPOINT}/" -H "X-API-Key: ${API_KEY}")
    # Accept 200 or 404 (app is responding, specific route may not exist)
    if [ "$status" -ge 200 ] && [ "$status" -lt 500 ] 2>/dev/null; then
        log_pass "App responds to requests (status: ${status})"
        return 0
    fi
    log_fail "App not responding (status: ${status})"
    return 1
}

run_test "Deploy blueprint" test_deploy_blueprint
run_test "Slices provisioned" test_slices_provisioned
run_test "Blueprint listed" test_blueprint_listed
run_test "App endpoint reachable" test_app_endpoint_reachable
run_test "App request succeeds" test_app_request_succeeds
print_summary
