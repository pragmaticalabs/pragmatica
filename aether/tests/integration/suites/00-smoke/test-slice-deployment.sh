#!/bin/bash
# test-slice-deployment.sh — Deploy url-shortener blueprint, verify requests
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "${SCRIPT_DIR}/../../lib/common.sh"
source "${SCRIPT_DIR}/../../lib/cluster.sh"

BLUEPRINT_COORDS="${TEST_BLUEPRINT_COORDS:-org.pragmatica.aether.test:test-echo:1.0.0}"
BLUEPRINT_NAME="${TEST_BLUEPRINT:-test-echo}"

test_push_artifacts() {
    push_blueprint "$BLUEPRINT_COORDS"
    log_pass "Blueprint artifacts pushed"
}

test_deploy_blueprint() {
    local result
    result=$(deploy_blueprint "$BLUEPRINT_COORDS")
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
    assert_contains "$blueprints" "$BLUEPRINT_NAME" "Blueprint visible in list"
}

test_app_endpoint_reachable() {
    # Use app_route_wired against a known slice route (EchoSlice exposes /health
    # via its health() method). app_route_wired distinguishes route-missing 404
    # (sendNoRouteFound problem+json) from a real handler response, so it proves
    # the slice route table is populated — not just that the TCP socket accepts.
    wait_for "EchoSlice /health route wired" \
        "app_route_wired \"${APP_ENDPOINT}/health\"" 60 || {
        log_fail "App route /health not wired within timeout"
        return 1
    }
    log_pass "App HTTP server responding (EchoSlice /health route wired)"
}

test_app_request_succeeds() {
    # The deployed EchoSlice serves /health → 200 OK with {"status":"healthy"}.
    # Strict: anything other than 200 (including 404, 401, 503) is a real failure.
    assert_http_status "${APP_ENDPOINT}/health" "200" \
        "EchoSlice /health returns 200" \
        -H "X-API-Key: ${API_KEY}"
}

run_test "Push artifacts" test_push_artifacts
run_test "Deploy blueprint" test_deploy_blueprint
run_test "Slices provisioned" test_slices_provisioned
run_test "Blueprint listed" test_blueprint_listed
run_test "App endpoint reachable" test_app_endpoint_reachable
run_test "App request succeeds" test_app_request_succeeds
print_summary
